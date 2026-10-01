// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionPolicyService;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unified session execution (design section 12 touchpoint 3, D8): the
 * orchestration budget trigger fires on the protocol 8.4 event type
 * {@code ORCHESTRATION_FUNCTION_COMPLETED} and reads the usage from the
 * NESTED {@code data.usage} map ({@code helperCalls} / {@code totalTokens})
 * - never the flat envelope keys. The notification is best-effort: a
 * policy-producer failure must never break event ingestion.
 *
 * <p>{@link SessionPolicyService} is mocked so the trigger observation is
 * the unit under test, not the whole policy-update wire path (that is
 * {@code ExecutionPolicyUpdateTest}'s scope).</p>
 */
@DisplayName("Unified execution: orchestration event ingestion budget trigger (nested usage)")
class OrchestrationEventIngestionPolicyTriggerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ExecutionEventRepository eventRepository;

    @Autowired
    private TaskAttemptRepository attemptRepository;

    @Autowired
    private ai.myrmec.engine.inference.execution.SessionExecutionRepository sessionExecutionRepository;

    private SessionPolicyService policyService;
    private OrchestrationEventIngestionService ingestionService;

    private TaskAttempt attempt;

    @BeforeEach
    void setUp() {
        policyService = Mockito.mock(SessionPolicyService.class);
        ingestionService = new OrchestrationEventIngestionService(
                eventRepository, attemptRepository, policyService,
                sessionExecutionRepository);

        Project project = data.project().named("ingest-trigger").withRepo("https://x.git", "main").create();
        AgentProfile profile = data.agentProfile().named("ingest-profile").create();
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        Workflow wf = new Workflow();
        wf.setProject(project);
        wf.setName("ingest-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(admin);
        workflowRepository.save(wf);

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/ingest");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        var request = workflowRequestRepository.save(req);

        WorkflowTask task = new WorkflowTask();
        task.setRequest(request);
        task.setStepId("build");
        task.setAgentProfile(profile);
        task.setInput(Map.of());
        task.setStatus(TaskStatus.RUNNING);
        task.setAttempt(1);
        task.setMaxRetries(1);
        task = workflowTaskRepository.save(task);

        attempt = attemptRepository.save(task.createAttempt(null));

        // The execution row bound to the dispatch (dispatch == attempt UUID)
        // - the seam the policy producer is keyed on.
        SessionExecution execution = new SessionExecution();
        execution.setSessionId(sessionOfWorkflow(project, request).getId());
        execution.setServiceType("WORKFLOW");
        execution.setRequestId(task.getId().toString());
        execution.setDispatchId(attempt.getId());
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(Instant.now());
        sessionExecutionRepository.save(execution);
    }

    /** A minimal WORKFLOW session row (allocation state irrelevant here). */
    private Session sessionOfWorkflow(Project project, WorkflowRequest request) {
        Session session = new Session();
        session.setServiceType("WORKFLOW");
        session.setRefId(request.getId());
        session.setProjectId(project.getId());
        session.setStatus("ACTIVE");
        session.setAllocationState(ai.myrmec.engine.inference.SessionAllocator.ALLOC_STATE_ACTIVE);
        return sessionRepository.save(session);
    }

    private Map<String, Object> nestedUsageEvent(long helperCalls, long tokens) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("helperCalls", helperCalls);
        usage.put("rejectionCount", 0);
        usage.put("totalTokens", tokens);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("callId", "call-1");
        data.put("outcome", "SUCCESS");
        data.put("usage", usage);
        return data;
    }

    @Test
    @DisplayName("ORCHESTRATION_FUNCTION_COMPLETED with nested usage triggers the policy producer")
    void nestedUsageTriggersPolicyProducer() {
        UUID eventId = UUID.randomUUID();

        var result = ingestionService.ingest(attempt.getId(), eventId, 1L,
                "ORCHESTRATION_FUNCTION_COMPLETED", nestedUsageEvent(4L, 1_200L));

        assertThat(result).isEqualTo(OrchestrationEventIngestionService.IngestResult.INSERTED);
        verify(policyService).onUsageRecorded(
                eq(sessionExecutionRepository.findByDispatchId(attempt.getId())
                        .orElseThrow().getId()),
                eq(4L), eq(1_200L));
    }

    @Test
    @DisplayName("flat usage keys on the envelope never trigger the producer")
    void flatUsageKeysDoNotTriggerPolicyProducer() {
        Map<String, Object> data = new HashMap<>();
        data.put("functionCalls", 7);
        data.put("totalTokens", 9_000L);

        ingestionService.ingest(attempt.getId(), UUID.randomUUID(), 1L,
                "ORCHESTRATION_FUNCTION_COMPLETED", data);

        verify(policyService, never()).onUsageRecorded(Mockito.any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("zero nested usage emits no notification; non-completion types never trigger")
    void zeroUsageAndOtherTypesDoNotTrigger() {
        ingestionService.ingest(attempt.getId(), UUID.randomUUID(), 1L,
                "ORCHESTRATION_FUNCTION_COMPLETED", nestedUsageEvent(0L, 0L));
        verify(policyService, never()).onUsageRecorded(Mockito.any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("a policy-producer failure never breaks event ingestion")
    void producerFailureDoesNotBreakIngestion() {
        UUID eventId = UUID.randomUUID();
        Mockito.doThrow(new RuntimeException("socket down"))
                .when(policyService).onUsageRecorded(Mockito.any(), anyLong(), anyLong());

        var result = ingestionService.ingest(attempt.getId(), eventId, 1L,
                "ORCHESTRATION_FUNCTION_COMPLETED", nestedUsageEvent(2L, 300L));

        assertThat(result).isEqualTo(OrchestrationEventIngestionService.IngestResult.INSERTED);
        assertThat(eventRepository.findBySourceEventId(eventId)).isPresent();
    }
}