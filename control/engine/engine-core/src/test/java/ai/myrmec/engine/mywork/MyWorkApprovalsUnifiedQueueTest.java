// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.mywork;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.mywork.dto.MyApprovalRow;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 1 #113 Integration Test: Unified My Work Approvals Queue
 * (Conversation + Execution Sources)
 *
 * <p>Tests that the My Work "Approvals" tab correctly aggregates both:
 * <ul>
 *   <li>Conversation-sourced approvals (APPROVAL_REQUEST messages with PENDING status)</li>
 *   <li>Execution-sourced approvals (WorkflowTask with approval_status=PENDING)</li>
 * </ul>
 *
 * <p>Exit criteria:
 * <ol>
 *   <li>✓ Conversation approval row appears in unified queue</li>
 *   <li>✓ Rows respect project scope filtering</li>
 *   <li>✓ Row structure has all required fields populated</li>
 * </ol>
 *
 * <p><strong>NOTE on Execution-Source Testing:</strong>
 * Execution approval infrastructure is complete (schema, service, entity, repository, DTO factory, 
 * and MyWorkService aggregation). However, H2 test schema lacks full Liquibase migration application,
 * so execution-source row construction is validated separately via ExecutionApprovalServiceTest
 * and MyApprovalRowFactoryTest. This test focuses on conversation-source approvals to ensure 
 * the unified My Work queue correctly surfaces approval requests.
 */
class MyWorkApprovalsUnifiedQueueTest extends IntegrationTestBase {

    @Autowired private TestDataBuilder data;
    @Autowired private MyWorkService myWorkService;
    @Autowired private ConversationService conversationService;
    @Autowired private ConversationMessageRepository conversationMessageRepository;
    @Autowired private ai.myrmec.engine.workflow.WorkflowRepository workflowRepository;
    @Autowired private ai.myrmec.engine.workflow.WorkflowRequestRepository requestRepository;
    @Autowired private ai.myrmec.engine.workflow.WorkflowTaskRepository workflowTaskRepository;

    @Test
    void conversationApprovalAppearsInMyWorkQueue() throws Exception {
        // ---------- Arrange ----------
        Project project = data.project().named("conv-approval-test").create();
        Authentication auth = adminAuth();

        // Create conversation with PENDING approval request (CONVERSATION source)
        var agent = data.agent()
                .named("test-agent")
                .withProfile(data.agentProfile()
                        .named("test-profile")
                        .withSystemPrompt("test")
                        .create())
                .inProject(project)
                .create();

        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID,
                "test-conv", agent.agent().getId(), null);

        // Create and persist conversation approval message directly
        ConversationMessage approvalMsg = new ConversationMessage();
        approvalMsg.setConversationId(conv.getId());
        approvalMsg.setRole(ConversationMessage.Role.APPROVAL_REQUEST);
        approvalMsg.setContent("DELETE user account");
        approvalMsg.setApprovalStatus(ConversationMessage.ApprovalStatus.PENDING);
        approvalMsg.setPayloadJson("{\"action\": \"delete_account\"}");
        approvalMsg.setCreatedAt(Instant.now());
        approvalMsg.setExpiresAt(Instant.now().plusSeconds(7200)); // 2 hours from now
        conversationMessageRepository.save(approvalMsg);

        // ---------- Act ----------
        List<MyApprovalRow> approvals = myWorkService.approvals(auth, List.of(project.getId()), null);

        // ---------- Assert ----------
        // Conversation approval row should appear in queue
        assertThat(approvals)
                .as("My Work approvals should include conversation-source approval")
                .anyMatch(r -> r.source() == MyApprovalRow.Source.CONVERSATION
                        && r.conversationId().equals(conv.getId())
                        && r.projectId().equals(project.getId()));
        
        MyApprovalRow convRow = approvals.stream()
                .filter(r -> r.source() == MyApprovalRow.Source.CONVERSATION
                        && r.conversationId().equals(conv.getId()))
                .findFirst()
                .orElseThrow();

        // Verify required fields are populated
        assertThat(convRow.messageId()).isNotNull();
        assertThat(convRow.conversationId()).isEqualTo(conv.getId());
        assertThat(convRow.projectId()).isEqualTo(project.getId());
        assertThat(convRow.summary()).isNotNull();
        assertThat(convRow.requestedAt()).isNotNull();
        assertThat(convRow.expiresAt()).isNotNull();
    }

    @Test
    void myWorkApprovalsRespectProjectScope() throws Exception {
        // ---------- Arrange ----------
        Project project1 = data.project().named("scope-test-1").create();
        Project project2 = data.project().named("scope-test-2").create();
        Authentication auth = adminAuth();

        // Create approval in project1
        var agent1 = data.agent()
                .named("test-agent-p1")
                .withProfile(data.agentProfile()
                        .named("test-profile-p1")
                        .withSystemPrompt("test")
                        .create())
                .inProject(project1)
                .create();

        Conversation conv1 = conversationService.createConversation(
                project1.getId(), TEST_ADMIN_ID,
                "test-conv-1", agent1.agent().getId(), null);

        ConversationMessage msg1 = new ConversationMessage();
        msg1.setConversationId(conv1.getId());
        msg1.setRole(ConversationMessage.Role.APPROVAL_REQUEST);
        msg1.setContent("Test approval 1");
        msg1.setApprovalStatus(ConversationMessage.ApprovalStatus.PENDING);
        msg1.setPayloadJson("{}");
        msg1.setCreatedAt(Instant.now());
        msg1.setExpiresAt(Instant.now().plusSeconds(3600));
        conversationMessageRepository.save(msg1);

        // Create approval in project2
        var agent2 = data.agent()
                .named("test-agent-p2")
                .withProfile(data.agentProfile()
                        .named("test-profile-p2")
                        .withSystemPrompt("test")
                        .create())
                .inProject(project2)
                .create();

        Conversation conv2 = conversationService.createConversation(
                project2.getId(), TEST_ADMIN_ID,
                "test-conv-2", agent2.agent().getId(), null);

        ConversationMessage msg2 = new ConversationMessage();
        msg2.setConversationId(conv2.getId());
        msg2.setRole(ConversationMessage.Role.APPROVAL_REQUEST);
        msg2.setContent("Test approval 2");
        msg2.setApprovalStatus(ConversationMessage.ApprovalStatus.PENDING);
        msg2.setPayloadJson("{}");
        msg2.setCreatedAt(Instant.now());
        msg2.setExpiresAt(Instant.now().plusSeconds(3600));
        conversationMessageRepository.save(msg2);

        // ---------- Act: Query only project1 scope ----------
        List<MyApprovalRow> rows = myWorkService.approvals(auth, List.of(project1.getId()), null);

        // ---------- Assert ----------
        assertThat(rows)
                .as("Scoped approvals should only include project1")
                .extracting(MyApprovalRow::projectId)
                .containsOnly(project1.getId());
        
        assertThat(rows)
                .extracting(MyApprovalRow::conversationId)
                .contains(conv1.getId())
                .doesNotContain(conv2.getId());
    }

    @Test
    void myWorkApprovalRowStructureIsCorrect() throws Exception {
        // ---------- Arrange ----------
        Project project = data.project().named("structure-test").create();
        Authentication auth = adminAuth();

        var agent = data.agent()
                .named("test-agent-struct")
                .withProfile(data.agentProfile()
                        .named("test-profile-struct")
                        .withSystemPrompt("test")
                        .create())
                .inProject(project)
                .create();

        Conversation conv = conversationService.createConversation(
                project.getId(), TEST_ADMIN_ID,
                "test-conv-struct", agent.agent().getId(), null);

        ConversationMessage msg = new ConversationMessage();
        msg.setConversationId(conv.getId());
        msg.setRole(ConversationMessage.Role.APPROVAL_REQUEST);
        msg.setContent("Test approval");
        msg.setApprovalStatus(ConversationMessage.ApprovalStatus.PENDING);
        msg.setPayloadJson("{\"test\": true}");
        msg.setCreatedAt(Instant.now());
        msg.setExpiresAt(Instant.now().plusSeconds(7200));
        conversationMessageRepository.save(msg);

        // ---------- Act ----------
        List<MyApprovalRow> rows = myWorkService.approvals(auth, List.of(project.getId()), null);

        // ---------- Assert ----------
        assertThat(rows).isNotEmpty();

        MyApprovalRow row = rows.get(0);
        // Verify required fields are populated
        assertThat(row.messageId()).isNotNull();
        assertThat(row.projectId()).isEqualTo(project.getId());
        assertThat(row.source()).isNotNull();
        assertThat(row.summary()).isNotNull();
        assertThat(row.requestedAt()).isNotNull();

        // For conversation source, these should be set
        if (row.source() == MyApprovalRow.Source.CONVERSATION) {
            assertThat(row.conversationId()).isNotNull();
        }
    }

    /**
     * Builds an {@link Authentication} whose principal is a real
     * {@link UserPrincipal}. {@code ProjectAccessEvaluator} reads scope and role
     * grants directly off the principal, so a bare token carrying only a raw id
     * resolves to no access. System {@code ORG_ADMIN} implies VIEWER on every
     * project, which is what the read-only My Work queue requires.
     */
    private Authentication adminAuth() {
        UserPrincipal principal = new UserPrincipal(
                TEST_ADMIN_ID, TEST_ADMIN_NAME, TEST_ADMIN_EMAIL,
                List.of("sys:ORG_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    /**
     * §17.4 approver targeting on the read side: the My Work approvals tab
     * narrows workflow-sourced (execution/orchestration) rows to the
     * triggering user ({@code WorkflowRequest.createdBy}) — a different
     * user with full project view access never sees the request, because
     * the decide path would reject them under the task row lock anyway.
     */
    @Test
    void workflowApprovalsNarrowToTheTriggeringUser() throws Exception {
        // ---------- Arrange: a full workflow graph with a PENDING approval ----------
        Project project = data.project().named("orch-visibility").create();
        var triggerer = userRepository.findById(TEST_ADMIN_ID).orElseThrow();
        var otherUser = new ai.myrmec.engine.user.User();
        otherUser.setEmail("orch-vis-other-" + System.nanoTime() + "@test.local");
        otherUser.setName("Orch visibility other");
        otherUser.setPasswordHash("$2a$10$dummy");
        otherUser.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        otherUser.setIsActive(true);
        otherUser.setIsSystem(false);
        otherUser.setCreatedAt(Instant.now());
        otherUser.setUpdatedAt(Instant.now());
        otherUser = userRepository.save(otherUser);

        var profile = data.agentProfile().named("orch-vis-profile").create();
        var wf = new ai.myrmec.engine.workflow.Workflow();
        wf.setProject(project);
        wf.setName("orch-vis-wf-" + System.nanoTime());
        wf.setSteps(java.util.List.<java.util.Map<String, Object>>of());
        wf.setVersion(1);
        wf.setStatus(ai.myrmec.engine.workflow.WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(triggerer);
        wf = workflowRepository.save(wf);

        var req = new ai.myrmec.engine.workflow.WorkflowRequest();
        req.setWorkflow(wf);
        req.setWorkflowVersion(1);
        req.setInput(java.util.Map.of());
        req.setStatus(ai.myrmec.engine.workflow.RequestStatus.PAUSED);
        req.setBranch("myrmec/vis");
        req.setCreatedBy(triggerer);
        req.setCreatedAt(Instant.now());
        req = requestRepository.save(req);

        var task = new ai.myrmec.engine.workflow.WorkflowTask();
        task.setRequest(req);
        task.setStepId("step-1");
        task.setAgentProfile(profile);
        task.setInput(java.util.Map.of());
        task.setStatus(ai.myrmec.engine.workflow.TaskStatus.PAUSED);
        task.setAttempt(1);
        task.setPauseState("ORCH_REVIEW");
        task.setApprovalStatus("PENDING");
        task.setApprovalRequestedAt(Instant.now());
        task.setApprovalPayload(java.util.Map.of(
                "approvalRequestId", java.util.UUID.randomUUID().toString(),
                "summary", "worker:coder:IMPLEMENT",
                "action", java.util.Map.of(
                        "actionId", "action-1", "type", "WORKER_TOOL",
                        "riskClass", "DESTRUCTIVE", "summary", "worker:coder:IMPLEMENT",
                        "digest", "a".repeat(64))));
        var savedTask = workflowTaskRepository.save(task);

        // ---------- Act ----------
        // The triggerer sees the pending orchestration approval...
        List<MyApprovalRow> triggererRows =
                myWorkService.approvals(triggererAuth(triggerer.getId()), List.of(project.getId()), null);
        // ...a different project member (ORCH_REVIEW requests are not
        // decidable by them, §17.4) does not.
        List<MyApprovalRow> otherRows =
                myWorkService.approvals(adminAuthOf(otherUser), List.of(project.getId()), null);

        // ---------- Assert ----------
        assertThat(triggererRows)
                .as("the triggering user sees the pending orchestration approval")
                .anyMatch(r -> r.source() == MyApprovalRow.Source.EXECUTION
                        && r.messageId().equals(savedTask.getId()));
        assertThat(otherRows)
                .as("no other user sees a workflow approval they cannot decide")
                .noneMatch(r -> r.source() == MyApprovalRow.Source.EXECUTION
                        && r.messageId().equals(savedTask.getId()));
        // The row carries the triggering user as requestedBy (§17.4).
        MyApprovalRow row = triggererRows.stream()
                .filter(r -> r.source() == MyApprovalRow.Source.EXECUTION
                        && r.messageId().equals(savedTask.getId()))
                .findFirst().orElseThrow();
        assertThat(row.requestedByUserId()).isEqualTo(triggerer.getId());
    }

    /** An ORG_ADMIN-scoped principal for a specific user id. */
    private Authentication adminAuthOf(ai.myrmec.engine.user.User user) {
        UserPrincipal principal = new UserPrincipal(
                user.getId(), user.getName(), user.getEmail(),
                List.of("sys:ORG_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private Authentication triggererAuth(UUID userId) {
        UserPrincipal principal = new UserPrincipal(
                userId, TEST_ADMIN_NAME, TEST_ADMIN_EMAIL,
                List.of("sys:ORG_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }
}
