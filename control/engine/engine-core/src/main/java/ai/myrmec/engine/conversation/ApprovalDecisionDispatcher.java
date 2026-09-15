// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.conversation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 7c unified protocol (P6-T6): records that a human approval decision
 * landed. The legacy approval.decision push over the conversation socket is
 * gone with that wire - under the unified protocol a conversation pause is
 * TERMINAL (8.6): the approval + decision rows are durable, and the next user
 * turn re-offers a fresh session whose execution.start carries
 * conversationContinuation (approvalRequestId/pendingActionId/digest), so
 * the host never needs a pushed frame to resume. The SDK re-fetches decided
 * requests via GET /api/v1/conversations/{id}/messages as before; the
 * "decided while agent offline" case is the normal flow, not a fallback.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalDecisionDispatcher {

    /**
     * Record the decision for a just-decided APPROVAL_REQUEST row. Both rows
     * are already persisted by ConversationService.submitApprovalDecision;
     * this hook remains so callers (and observability) see the decision event.
     */
    public void dispatch(UUID conversationId, ConversationService.ApprovalDecisionResult result) {
        log.info("Approval decision recorded for conv {} (request {}, decision {}) - "
                        + "resumes via conversationContinuation on the next turn",
                conversationId, result.request().getId(),
                result.request().getApprovalStatus());
    }
}