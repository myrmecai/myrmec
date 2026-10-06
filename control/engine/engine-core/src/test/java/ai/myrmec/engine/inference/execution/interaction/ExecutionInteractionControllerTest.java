// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload;
import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.WorkflowStatus;
import ai.myrmec.engine.workflow.TaskAttempt;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import ai.myrmec.engine.workflow.TaskStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowRequestRepository;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6 (plan section 4 routes): the three control routes under the base
 * ownership chain — POST /controls (HOLD/CONTINUE, 202), POST /cancel
 * (confirmed, 202), POST /control-requests/{controlRequestId}/decision
 * (200). Covers the section-4 error table: 404 ownership mismatch, 409
 * reason codes (EXECUTION_TERMINAL / CONFIRMATION_EXPIRED), DUPLICATE_CODE
 * for a reused client id with different bytes, and the platform-admin
 * isolation rule.
 */
class ExecutionInteractionControllerTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executions;
    @Autowired ExecutionInteractionRepository interactions;
    @Autowired ExecutionControlRequestRepository controlRequests;
    @Autowired ExecutionCommandOutboxRepository outboxRepository;
    @Autowired WorkflowRequestRepository requestRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired WorkflowTaskRepository taskRepository;
    @Autowired TaskAttemptRepository attemptRepository;
    @Autowired AgentProfileRepository agentProfileRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired UserRepository userRepository;
    @Autowired UserRoleRepository userRoleRepository;

    private Project project;
    private Workflow workflow;
    private WorkflowRequest request;
    private WorkflowTask task;
    private TaskAttempt attempt;
    private Session session;
    private SessionExecution execution;
    private User editorUser;
    private User viewerUser;

    @BeforeEach
    void seedChain() {
        project = data.project().named("ctrl-rest-proj").create();
        workflow = workflowOf(project);
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflow);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/ctrl-rest");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        AgentProfile profile = agentProfileRepository.findAll().stream().findFirst()
                .orElseGet(() -> data.agentProfile().named("ctrl-rest-profile").create());
        WorkflowTask t = new WorkflowTask();
        t.setRequest(request);
        t.setStepId("step-1");
        t.setAgentProfile(profile);
        t.setInput(Map.of());
        t.setStatus(TaskStatus.RUNNING);
        t.setAttempt(1);
        t.setMaxRetries(1);
        task = taskRepository.save(t);
        attempt = attemptRepository.save(task.createAttempt(null));

        AgentHostInstance instance = instances.saveAndFlush(AgentHostInstance.open(
                data.agent().named("ctrl-rest-host").create().agent(), null,
                UUID.randomUUID().toString(), "laptop", 4, Map.of(), "node-1"));

        session = new Session();
        session.setServiceType("WORKFLOW");
        session.setRefId(request.getId());
        session.setProjectId(project.getId());
        session.setKind("ORCHESTRATION_TASK");
        session.setStatus("ACTIVE");
        session.setAllocationState("ACTIVE");
        session.setHostInstanceId(instance.getId());
        session = sessionRepository.save(session);

        execution = new SessionExecution();
        execution.setSessionId(session.getId());
        execution.setServiceType("WORKFLOW");
        execution.setDispatchId(attempt.getId());
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(Instant.now());
        execution.setDeadline(Instant.now().plusSeconds(600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        execution.setInteractionPolicy(InteractionPolicySnapshotService.policyMap(
                InteractionProperties.defaults()));
        execution = executions.saveAndFlush(execution);

        editorUser = seedUser("rest-editor");
        viewerUser = seedUser("rest-viewer");
        grant(editorUser.getId(), UserRole.Role.EDITOR, project.getId());
        grant(viewerUser.getId(), UserRole.Role.VIEWER, project.getId());
    }

    // ------------------------------------------------------------------
    // Route shapes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /controls: editor HOLD → 202 with controlRequestId + controlRevision + status")
    void postControlReturns202() {
        ResponseEntity<String> response = post(basePath() + "/controls",
                editorHeaders(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"action\":\"HOLD\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = read(response);
        assertThat(body.path("controlRequestId").asText()).isNotBlank();
        assertThat(body.path("controlRevision").asLong()).isEqualTo(1L);
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");

        assertThat(rows()).hasSize(1);
        assertThat(outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId())).hasSize(1);
    }

    @Test
    @DisplayName("POST /controls: viewer → 403 FORBIDDEN; platform-only admin → 403")
    void postControlForbidden() {
        ResponseEntity<String> viewer = post(basePath() + "/controls",
                headers(viewerUser.getId(),
                        List.of("proj:" + project.getId() + ":VIEWER")),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"action\":\"HOLD\"}");
        assertThat(viewer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(read(viewer).path("errorCode").asText()).isEqualTo("FORBIDDEN");

        ResponseEntity<String> platformOnly = post(basePath() + "/controls",
                headers(editorUser.getId(), List.of("sys:PLATFORM_ADMIN")),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"action\":\"HOLD\"}");
        assertThat(platformOnly.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("POST /controls: invalid action → 400 VALIDATION_ERROR")
    void postControlInvalidAction400() {
        ResponseEntity<String> response = post(basePath() + "/controls",
                editorHeaders(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"action\":\"PAUSE\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(response).path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("POST /controls: same clientRequestId different bytes → 409 DUPLICATE_CODE")
    void postControlDuplicateClientFails() {
        UUID same = UUID.randomUUID();
        assertThat(post(basePath() + "/controls", editorHeaders(),
                "{\"clientRequestId\":\"" + same + "\",\"action\":\"HOLD\"}")
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> second = post(basePath() + "/controls", editorHeaders(),
                "{\"clientRequestId\":\"" + same + "\",\"action\":\"CONTINUE\"}");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(read(second).path("errorCode").asText()).isEqualTo("DUPLICATE_CODE");
        assertThat(rows()).hasSize(1);
    }

    @Test
    @DisplayName("POST /controls: foreign attemptId in the chain → 404 RESOURCE_NOT_FOUND")
    void postControlForeignAttempt404() {
        String foreign = "/api/v1/projects/" + project.getId()
                + "/workflows/" + workflow.getId()
                + "/requests/" + request.getId()
                + "/tasks/" + task.getId()
                + "/attempts/" + UUID.randomUUID()
                + "/executions/" + execution.getId() + "/controls";
        ResponseEntity<String> response = post(foreign, editorHeaders(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"action\":\"HOLD\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read(response).path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("POST /controls on a terminal execution → 409 reasonCode EXECUTION_TERMINAL")
    void postControlOnTerminal409() {
        execution.setState(SessionExecution.State.PAUSED);
        execution.setTerminalMessageId("tm-paused");
        executions.saveAndFlush(execution);

        ResponseEntity<String> response = post(basePath() + "/controls", editorHeaders(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"action\":\"CONTINUE\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = read(response);
        assertThat(body.path("errorCode").asText()).isEqualTo("RESOURCE_IN_USE");
        // §4 reasonCode: the control-route handler maps the service's
        // rejection onto details[0].reasonCode (EXECUTION_TERMINAL).
        JsonNode firstDetail = body.path("details").isArray()
                ? body.path("details").get(0)
                : body.path("details");
        assertThat(firstDetail.path("reasonCode").asText())
                .isEqualTo("EXECUTION_TERMINAL");
    }

    @Test
    @DisplayName("POST /cancel: confirmed:true → 202 ACCEPTED; creates the SAME intent/outbox shapes")
    void postCancelConfirmed202() {
        ResponseEntity<String> response = post(basePath() + "/cancel", editorHeaders(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"confirmed\":true}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode body = read(response);
        assertThat(body.path("controlRequestId").asText()).isNotBlank();
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");

        List<ExecutionControlRequest> rows = rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo("CANCEL");
        assertThat(rows.get(0).getOrigin()).isEqualTo("BUTTON");
        assertThat(rows.get(0).getCommandMessageId()).isNotNull();
        List<ExecutionCommandOutbox> outbox = outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId());
        assertThat(outbox).hasSize(1);
        assertThat(outbox.get(0).getType()).isEqualTo("execution.cancel");
    }

    @Test
    @DisplayName("POST /cancel: confirmed:false → 400 VALIDATION_ERROR (no implicit confirmation)")
    void postCancelUnconfirmed400() {
        ResponseEntity<String> response = post(basePath() + "/cancel", editorHeaders(),
                "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"confirmed\":false}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(read(response).path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(rows()).isEmpty();
    }

    @Test
    @DisplayName("decision route: CONFIRM → 200 ACCEPTED with exactly one cancel command")
    void decisionConfirmsProposal() {
        UUID proposalId = seedChatCancel();

        ResponseEntity<String> response = post(
                basePath() + "/control-requests/" + proposalId + "/decision",
                editorHeaders(), "{\"decision\":\"CONFIRM\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = read(response);
        assertThat(body.path("controlRequestId").asText()).isEqualTo(proposalId.toString());
        assertThat(body.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(body.path("commandMessageId").asText()).isNotBlank();
        assertThat(outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId())).hasSize(1);
    }

    @Test
    @DisplayName("decision route: DECLINE → 200 DECLINED with no command")
    void decisionDeclinesProposal() {
        UUID proposalId = seedChatCancel();

        ResponseEntity<String> response = post(
                basePath() + "/control-requests/" + proposalId + "/decision",
                editorHeaders(), "{\"decision\":\"DECLINE\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(read(response).path("status").asText()).isEqualTo("DECLINED");
        assertThat(outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId())).isEmpty();
    }

    @Test
    @DisplayName("decision route: viewer → 403; unknown proposal → 404")
    void decisionForbiddenOr404() {
        UUID proposalId = seedChatCancel();

        ResponseEntity<String> forbidden = post(
                basePath() + "/control-requests/" + proposalId + "/decision",
                headers(viewerUser.getId(), List.of("proj:" + project.getId() + ":VIEWER")),
                "{\"decision\":\"CONFIRM\"}");
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> notFound = post(
                basePath() + "/control-requests/" + UUID.randomUUID() + "/decision",
                editorHeaders(), "{\"decision\":\"CONFIRM\"}");
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(read(notFound).path("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("decision route: expired confirmation → 409 §4 envelope RESOURCE_IN_USE + reasonCode CONFIRMATION_EXPIRED")
    void decisionExpired409() {
        UUID proposalId = seedChatCancel();
        ageExpiry(proposalId, -5);

        ResponseEntity<String> response = post(
                basePath() + "/control-requests/" + proposalId + "/decision",
                editorHeaders(), "{\"decision\":\"CONFIRM\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = read(response);
        // §4: the canonical error envelope — the SAME shape the terminal
        // 409s on this controller produce (NOT a receipt body).
        assertThat(body.path("errorCode").asText()).isEqualTo("RESOURCE_IN_USE");
        JsonNode firstDetail = body.path("details").isArray()
                ? body.path("details").get(0)
                : body.path("details");
        assertThat(firstDetail.path("reasonCode").asText()).isEqualTo("CONFIRMATION_EXPIRED");
        // The row is settled EXPIRED durably either way.
        assertThat(rows().stream()
                .filter(r -> r.getId().equals(proposalId))
                .findFirst().orElseThrow().getStatus())
                .isEqualTo(InteractionControlStatus.EXPIRED);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    @Test
    @DisplayName("POST /interactions: secret-shaped text → 400 VALIDATION_ERROR with details[0].field=text errorCode=CAPTURE_BLOCKED (never 500)")
    void admissionCaptureBlockedMapsTo400() {
        ResponseEntity<String> response = post(basePath() + "/interactions",
                editorHeaders(),
                "{\"clientRequestId\":\"" + UUID.randomUUID()
                        + "\",\"text\":\"the key is myr_agent_aaaaabbbbbccccc keep it\"}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = read(response);
        assertThat(body.path("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        JsonNode firstDetail = body.path("details").isArray()
                ? body.path("details").get(0)
                : body.path("details");
        assertThat(firstDetail.path("field").asText()).isEqualTo("text");
        assertThat(firstDetail.path("errorCode").asText()).isEqualTo("CAPTURE_BLOCKED");
        // Nothing persisted.
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .isEmpty();
    }

    private String basePath() {
        return "/api/v1/projects/" + project.getId()
                + "/workflows/" + workflow.getId()
                + "/requests/" + request.getId()
                + "/tasks/" + task.getId()
                + "/attempts/" + attempt.getId()
                + "/executions/" + execution.getId();
    }

    /** Seed an admitted chat + a CONFIRMATION_REQUIRED chat-CANCEL proposal. */
    private UUID seedChatCancel() {
        ExecutionInteraction interaction = new ExecutionInteraction();
        interaction.setExecutionId(execution.getId());
        interaction.setOrdinal(1L);
        interaction.setActorUserId(editorUser.getId());
        interaction.setClientRequestId(UUID.randomUUID());
        interaction.setRequestDigest("a".repeat(64));
        interaction.setStatus(InteractionStatus.ACCEPTED);
        interaction.setRequestText("cancel please");
        interaction.setResponseDeadline(Instant.now().plusSeconds(120)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        interaction.setAcceptedAt(Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        interaction.setUsageStatus("UNKNOWN");
        interaction = interactions.saveAndFlush(interaction);
        SessionExecution locked = executions.findById(execution.getId()).orElseThrow();
        locked.setPendingInteractionId(interaction.getId());
        executions.save(locked);

        var payload = new ExecutionControlRequestPayload(execution.getId(),
                attempt.getId(), interaction.getId(), UUID.randomUUID(),
                ExecutionControlRequestPayload.Action.CANCEL, "stop");
        ProposalReceipt proposal = service().propose(scope(), payload);
        return proposal.controlRequestId();
    }

    private void ageExpiry(UUID requestId, long seconds) {
        var row = controlRequests.findById(requestId).orElseThrow();
        row.setConfirmationExpiresAt(Instant.now().plusSeconds(seconds)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        controlRequests.save(row);
    }

    @Autowired
    org.springframework.context.ApplicationContext applicationContext;

    private ExecutionControlService service() {
        return applicationContext.getBean(ExecutionControlService.class);
    }

    private List<ExecutionControlRequest> rows() {
        return controlRequests.findByExecutionIdOrderByCreatedAtAsc(execution.getId());
    }

    private ExecutionScope scope() {
        return new ExecutionScope(project.getId(), workflow.getId(), request.getId(),
                task.getId(), attempt.getId(), execution.getId());
    }

    private ResponseEntity<String> post(String path, HttpHeaders hs, String json) {
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(json, hs), String.class);
    }

    private HttpHeaders editorHeaders() {
        return headers(editorUser.getId(),
                List.of("proj:" + project.getId() + ":EDITOR"));
    }

    private HttpHeaders headers(UUID userId, List<String> roles) {
        HttpHeaders hs = new HttpHeaders();
        hs.setBearerAuth(jwtTokenProvider.generateUserAccessToken(userId,
                "Test User", "user-" + userId.toString().substring(0, 8)
                        + "@test.local", roles));
        hs.set("Content-Type", "application/json");
        return hs;
    }

    @SuppressWarnings("unused")
    private JsonNode read(ResponseEntity<String> response) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("Unparseable response body", e);
        }
    }

    private User seedUser(String suffix) {
        User user = new User();
        user.setEmail("ctrl-rest-" + suffix + "-" + System.nanoTime() + "@test.local");
        user.setName("Ctrl Rest " + suffix);
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setIsSystem(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private void grant(UUID userId, UserRole.Role role, UUID projectId) {
        UserRole row = new UserRole();
        row.setUserId(userId);
        row.setRole(role);
        row.setScopeType(UserRole.ScopeType.PROJECT);
        row.setProjectId(projectId);
        row.setGrantedByUserId(TEST_ADMIN_ID);
        userRoleRepository.save(row);
    }

    private Workflow workflowOf(Project parent) {
        Workflow wf = new Workflow();
        wf.setProject(parent);
        wf.setName("ctrl-rest-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return workflowRepository.save(wf);
    }
}
