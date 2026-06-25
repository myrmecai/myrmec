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
}
