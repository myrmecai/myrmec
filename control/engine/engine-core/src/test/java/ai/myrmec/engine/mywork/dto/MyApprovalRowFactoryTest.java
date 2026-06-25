// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.mywork.dto;

import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link MyApprovalRow} factory methods — the pure mapping
 * layer that projects a conversation HITL request or an execution-source
 * DESTRUCTIVE tool call into a unified My Work approval row (#113).
 *
 * <p>No Spring context: the factories are static and operate on plain entity
 * graphs, so the mapping can be asserted field-by-field in isolation.
 */
class MyApprovalRowFactoryTest {

    @Test
    void fromConversationMapsAllFields() {
        UUID messageId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID assistantId = UUID.randomUUID();
        UUID createdBy = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant expiresAt = createdAt.plusSeconds(7200);

        Conversation conversation = new Conversation();
        conversation.setId(conversationId);
        conversation.setProjectId(projectId);
        conversation.setAssistantId(assistantId);
        conversation.setCreatedBy(createdBy);
        conversation.setExternalUserRef("ext-user-42");

        ConversationMessage request = new ConversationMessage();
        request.setId(messageId);
        request.setConversationId(conversationId);
        request.setRole(ConversationMessage.Role.APPROVAL_REQUEST);
        request.setContent("DELETE user account");
        request.setPayloadJson("{\"action\":\"delete_account\"}");
        request.setCreatedAt(createdAt);
        request.setExpiresAt(expiresAt);

        MyApprovalRow row = MyApprovalRow.fromConversation(request, conversation, "Acme Project");

        assertThat(row.messageId()).isEqualTo(messageId);
        assertThat(row.conversationId()).isEqualTo(conversationId);
        assertThat(row.projectId()).isEqualTo(projectId);
        assertThat(row.projectName()).isEqualTo("Acme Project");
        assertThat(row.source()).isEqualTo(MyApprovalRow.Source.CONVERSATION);
        assertThat(row.assistantId()).isEqualTo(assistantId);
        assertThat(row.summary()).isEqualTo("DELETE user account");
        assertThat(row.payloadJson()).isEqualTo("{\"action\":\"delete_account\"}");
        assertThat(row.requestedByUserId()).isEqualTo(createdBy);
        assertThat(row.externalUserRef()).isEqualTo("ext-user-42");
        assertThat(row.requestedAt()).isEqualTo(createdAt);
        assertThat(row.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    void fromExecutionUsesPayloadSummaryWhenPresent() {
        WorkflowTask task = executionTask(
                Map.of("summary", "Drop customers table"), "step-7");

        MyApprovalRow row = MyApprovalRow.fromExecution(task, "Ops Project");

        assertThat(row.source()).isEqualTo(MyApprovalRow.Source.EXECUTION);
        assertThat(row.projectName()).isEqualTo("Ops Project");
        assertThat(row.conversationId()).isNull();
        assertThat(row.assistantId()).isNull();
        assertThat(row.payloadJson()).isNull();
        assertThat(row.summary()).isEqualTo("Drop customers table");
    }

    @Test
    void fromExecutionFallsBackToStepIdWhenNoPayload() {
        WorkflowTask task = executionTask(null, "step-7");

        MyApprovalRow row = MyApprovalRow.fromExecution(task, "Ops Project");

        assertThat(row.source()).isEqualTo(MyApprovalRow.Source.EXECUTION);
        assertThat(row.summary()).isEqualTo("step-7");
    }

    private static WorkflowTask executionTask(Map<String, Object> approvalPayload, String stepId) {
        UUID projectId = UUID.randomUUID();
        Project project = new Project();
        project.setId(projectId);

        Workflow workflow = new Workflow();
        workflow.setProject(project);

        WorkflowRequest request = new WorkflowRequest();
        request.setWorkflow(workflow);

        WorkflowTask task = new WorkflowTask();
        task.setId(UUID.randomUUID());
        task.setRequest(request);
        task.setStepId(stepId);
        task.setApprovalPayload(approvalPayload);
        task.setApprovalRequestedAt(Instant.now());
        task.setApprovalExpiresAt(Instant.now().plusSeconds(3600));

        assertThat(task.getRequest().getWorkflow().getProject().getId()).isEqualTo(projectId);
        return task;
    }
}
