package ai.myrmec.engine.mywork.dto;

import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.workflow.WorkflowTask;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the My Work "Approvals" tab (UC-013) — a single pending human
 * decision. V1 sources approvals from conversation HITL gates only
 * ({@code APPROVAL_REQUEST} message rows with {@code approvalStatus = PENDING});
 * the {@code source} facet is {@code CONVERSATION}. Workflow-execution approvals
 * ({@code EXECUTION} source) join once the execution model grows an
 * {@code AWAITING_APPROVAL} state.
 *
 * <p>{@code requestedByUserId} / {@code externalUserRef} identify whose work hit
 * the gate (the conversation owner), not the agent that raised the request.
 */
public record MyApprovalRow(
        UUID messageId,
        UUID conversationId,
        UUID projectId,
        String projectName,
        Source source,
        UUID assistantId,
        String summary,
        String payloadJson,
        UUID requestedByUserId,
        String externalUserRef,
        Instant requestedAt,
        Instant expiresAt) {

    /** Origin of the approval, surfaced as the tab's {@code Source} facet. */
    public enum Source {
        EXECUTION,
        CONVERSATION
    }

    /** Build a conversation-sourced approval row from the request message + its conversation. */
    public static MyApprovalRow fromConversation(
            ConversationMessage request, Conversation conversation, String projectName) {
        return new MyApprovalRow(
                request.getId(),
                conversation.getId(),
                conversation.getProjectId(),
                projectName,
                Source.CONVERSATION,
                conversation.getAssistantId(),
                request.getContent(),
                request.getPayloadJson(),
                conversation.getCreatedBy(),
                conversation.getExternalUserRef(),
                request.getCreatedAt(),
                request.getExpiresAt());
    }

    /** Build an execution-sourced approval row from a DESTRUCTIVE tool call awaiting approval. */
    public static MyApprovalRow fromExecution(WorkflowTask task, String projectName) {
        return new MyApprovalRow(
                task.getId(),
                null, // no conversation context for execution approvals
                task.getRequest().getWorkflow().getProject().getId(),
                projectName,
                Source.EXECUTION,
                null, // no assistant context
                (String) (task.getApprovalPayload() != null ? task.getApprovalPayload().get("summary") : task.getStepId()),
                null, // payloadJson not used for execution; approval_payload is JSONB
                null, // execution approvals not tied to a user; task.request resolves to workflow request
                null, // no external user ref
                task.getApprovalRequestedAt(),
                task.getApprovalExpiresAt());
    }
}
