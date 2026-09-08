package ai.myrmec.engine.conversation;

import ai.myrmec.engine.websocket.ConversationSocketRegistry;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.ApprovalDecisionPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7e — flips APPROVAL_REQUEST rows past their {@code expiresAt}
 * from PENDING to EXPIRED in a dedicated transaction per row, then
 * dispatches an {@code approval.decision} frame (with
 * {@code decision = EXPIRED}) so the agent SDK's pending future wakes
 * up.
 *
 * <p>The 7a {@code submitApprovalDecision} path raises a runtime
 * exception when a human tries to decide a past-expiry request — that
 * rollback means the EXPIRED status is never durably written through
 * the REST surface. The sweeper is the *authoritative* writer of the
 * EXPIRED state; once a row has been swept the SDK can no longer be
 * surprised by a late human decision (the row is no longer PENDING).</p>
 *
 * <p>Each row is processed in its own {@link Propagation#REQUIRES_NEW}
 * transaction so a transient failure (DB hiccup, JSON serialisation
 * error during broadcast) on one row never aborts the whole sweep.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalExpirySweeper {

    private final ConversationMessageRepository messageRepository;
    private final ConversationStreamBroker streamBroker;
    private final ConversationSocketRegistry conversationSocketRegistry;
    private final ObjectMapper objectMapper;
    // HITL slice B: the orchestration-review sweep (§17.4/§16.6).
    private final ai.myrmec.engine.workflow.WorkflowTaskRepository workflowTaskRepository;
    private final ai.myrmec.engine.workflow.OrchestrationApprovalService orchestrationApprovalService;

    /** Disabled by default in tests that don't want timing pressure. */
    @Value("${myrmec.hitl.expiry-sweeper.enabled:true}")
    private boolean enabled;

    /**
     * Pulled from configuration so tests can override to a tight cadence
     * without waiting a full minute. Production default is 60s.
     */
    @Scheduled(fixedRateString = "${myrmec.hitl.expiry-sweeper.interval-ms:60000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        sweepConversationApprovals();
        // §17.4/§16.6 (HITL slice B): orchestration-review tasks past
        // their approval expiry apply the terminal APPROVAL_EXPIRED
        // tuple through the orchestration-aware decide path.
        try {
            sweepOrchestrationReviews();
        } catch (Exception e) {
            log.warn("Orchestration-review sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * §17.4/§16.6: sweep {@code pauseState=ORCH_REVIEW} tasks whose
     * {@code approval_expires_at} has passed — each applies the terminal
     * {@code APPROVAL_EXPIRED} tuple (task COMPLETED/FAILURE, request
     * FAILED, no continuation attempt, workspace release). Per-row
     * failures never block the rest.
     */
    private void sweepOrchestrationReviews() {
        Instant cutoff = Instant.now();
        List<ai.myrmec.engine.workflow.WorkflowTask> due;
        try {
            due = workflowTaskRepository
                    .findByPauseStateAndApprovalExpiresAtBefore("ORCH_REVIEW", cutoff);
        } catch (Exception e) {
            log.warn("Orchestration-review sweep query failed: {}", e.getMessage(), e);
            return;
        }
        for (ai.myrmec.engine.workflow.WorkflowTask task : due) {
            if (!"PENDING".equals(task.getApprovalStatus())) {
                continue; // already decided
            }
            try {
                orchestrationApprovalService.expire(task.getId());
                log.info("Orchestration approval for task {} expired by sweeper", task.getId());
            } catch (Exception e) {
                log.warn("Failed to expire orchestration approval for task {}: {}",
                        task.getId(), e.getMessage(), e);
            }
        }
    }

    private void sweepConversationApprovals() {
        Instant cutoff = Instant.now();
        List<ConversationMessage> due;
        try {
            due = messageRepository.findByRoleAndApprovalStatusAndExpiresAtBefore(
                    ConversationMessage.Role.APPROVAL_REQUEST,
                    ConversationMessage.ApprovalStatus.PENDING,
                    cutoff);
        } catch (Exception e) {
            log.warn("Expiry sweeper query failed: {}", e.getMessage(), e);
            return;
        }
        if (due.isEmpty()) {
            return;
        }
        log.debug("Expiry sweeper found {} PENDING approval(s) past cutoff {}", due.size(), cutoff);
        for (ConversationMessage row : due) {
            try {
                expireOne(row.getId());
            } catch (Exception e) {
                log.warn("Failed to expire approval {}: {}", row.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * Marks one approval row EXPIRED + broadcasts + pushes a decision
     * frame to any ONLINE pinned agent. Each call runs in its own
     * transaction so a single broken row never blocks the rest.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireOne(UUID messageId) {
        ConversationMessage row = messageRepository.findById(messageId).orElse(null);
        if (row == null
                || row.getRole() != ConversationMessage.Role.APPROVAL_REQUEST
                || row.getApprovalStatus() != ConversationMessage.ApprovalStatus.PENDING) {
            // Row vanished or someone decided between the query and the
            // per-row transaction — nothing to do.
            return;
        }
        row.setApprovalStatus(ConversationMessage.ApprovalStatus.EXPIRED);
        messageRepository.save(row);
        log.info("Approval {} on conv {} expired by sweeper",
                row.getId(), row.getConversationId());

        // Broadcast a decision envelope so the chat UI flips the badge
        // without a manual refresh.
        ApprovalDecisionPayload payload = ApprovalDecisionPayload.builder()
                .conversationId(row.getConversationId())
                .requestMessageId(row.getId())
                .clientRequestId(extractClientRequestId(row))
                .decision(ConversationMessage.ApprovalStatus.EXPIRED.name())
                .build();
        try {
            String json = objectMapper.writeValueAsString(
                    WebSocketMessage.of(MessageType.APPROVAL_DECISION, payload));
            streamBroker.broadcast(row.getConversationId(), json);
        } catch (Exception e) {
            log.warn("Expiry broadcast for approval {} failed: {}", row.getId(), e.getMessage());
        }

        // Wake the worker awaiting the request_approval() future by
        // pushing the EXPIRED decision over its conversation socket.
        conversationSocketRegistry.sendMessage(
                row.getConversationId(),
                WebSocketMessage.of(MessageType.APPROVAL_DECISION, payload));
    }

    /**
     * Same extraction strategy as {@link ApprovalDecisionDispatcher} —
     * the agent SDK stamps a top-level {@code "clientRequestId"} key
     * into the payloadJson when it calls {@code ctx.request_approval}.
     */
    private UUID extractClientRequestId(ConversationMessage row) {
        String json = row.getPayloadJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        String key = "\"clientRequestId\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = json.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        int closingQuote = json.indexOf('"', firstQuote + 1);
        if (closingQuote < 0) {
            return null;
        }
        try {
            return UUID.fromString(json.substring(firstQuote + 1, closingQuote));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
