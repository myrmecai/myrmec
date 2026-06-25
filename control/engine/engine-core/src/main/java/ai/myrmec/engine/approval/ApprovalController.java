// SPDX-License-Identifier: Apache-2.0
// Copyright 2025 The Myrmec Authors

package ai.myrmec.engine.approval;

import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationMessageRepository;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.ApprovalDecisionDispatcher;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.workflow.ExecutionApprovalDecisionDispatcher;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Phase 3 — Unified REST endpoint for HITL approval decisions.
 *
 * <p>Routes approval decisions to the correct dispatcher based on source:
 * - CONVERSATION: Routes to ApprovalDecisionDispatcher (sends to agent WebSocket)
 * - EXECUTION: Routes to ExecutionApprovalDecisionDispatcher (updates task state)
 */
@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Approvals", description = "Human-in-the-loop approval decisions")
public class ApprovalController {

    private final ConversationService conversationService;
    private final ApprovalDecisionDispatcher approvalDecisionDispatcher;
    private final ExecutionApprovalDecisionDispatcher executionApprovalDecisionDispatcher;
    private final ConversationMessageRepository conversationMessageRepository;
    private final WorkflowTaskRepository workflowTaskRepository;

    /**
     * Submit a human decision (APPROVED / REJECTED) for an APPROVAL_REQUEST.
     *
     * <p>This unified endpoint handles both:
     * - Conversation-source approvals (from ConversationMessage with role APPROVAL_REQUEST)
     * - Execution-source approvals (from WorkflowTask with approval_status=PENDING)
     *
     * @param approvalId The ID of the approval (message ID for conversation, task ID for execution)
     * @param body The decision payload
     * @param userId The approver's user ID
     * @return 200 OK with the updated approval row
     */
    @PostMapping("/{approvalId}/decide")
    @Operation(summary = "Submit a human decision for an approval request (conversation or execution)")
    @PreAuthorize("hasAnyRole('APPROVER', 'PROJECT_OWNER', 'ORG_ADMIN')")
    public ResponseEntity<ApprovalDecisionResponse> submitDecision(
            @PathVariable UUID approvalId,
            @Valid @RequestBody ApprovalDecisionRequest body,
            @AuthenticationPrincipal UserPrincipal principal) {

        UUID userId = principal.getUserId();
        String decision = body.decision();
        String comment = body.comment();

        // Determine if this is a conversation or execution approval by checking both sources
        ConversationMessage conversationMessage = conversationMessageRepository.findById(approvalId).orElse(null);
        WorkflowTask workflowTask = workflowTaskRepository.findById(approvalId).orElse(null);

        if (conversationMessage != null && conversationMessage.getRole() == ConversationMessage.Role.APPROVAL_REQUEST) {
            // Conversation-source approval
            log.info("Processing conversation approval {} for user {}", approvalId, userId);
            handleConversationApproval(conversationMessage, userId, decision, comment);
            
            return ResponseEntity.ok(ApprovalDecisionResponse.builder()
                    .source("CONVERSATION")
                    .approvalId(approvalId.toString())
                    .decision(decision)
                    .build());
        } else if (workflowTask != null && workflowTask.getApprovalStatus() != null) {
            // Execution-source approval
            log.info("Processing execution approval {} for user {}", approvalId, userId);
            executionApprovalDecisionDispatcher.dispatch(approvalId, decision);
            
            return ResponseEntity.ok(ApprovalDecisionResponse.builder()
                    .source("EXECUTION")
                    .approvalId(approvalId.toString())
                    .decision(decision)
                    .build());
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Handle conversation-source approval decision.
     */
    private void handleConversationApproval(
            ConversationMessage message,
            UUID userId,
            String decision,
            String comment) {
        UUID conversationId = message.getConversationId();
        
        ConversationService.ApprovalDecisionResult result =
                conversationService.submitApprovalDecision(
                        conversationId, message.getId(), userId,
                        ConversationMessage.ApprovalStatus.valueOf(decision), comment);
        
        // Best-effort push to agent WebSocket
        try {
            approvalDecisionDispatcher.dispatch(conversationId, result);
        } catch (Exception e) {
            log.warn("approval.decision dispatch threw for conv {} msg {}: {}",
                    conversationId, message.getId(), e.getMessage(), e);
        }
    }
}
