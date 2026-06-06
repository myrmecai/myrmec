package ai.myrmec.engine.conversation;

import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
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
    private final ConversationRepository conversationRepository;
    private final ConversationStreamBroker streamBroker;
    private final AgentInstanceRepository agentInstanceRepository;
    private final AgentConnectionManager connectionManager;
    private final AgentWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

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

        // Wake any agent that's currently awaiting the request_approval()
        // future on this clientRequestId.
        conversationRepository.findById(row.getConversationId()).ifPresent(conv -> {
            if (conv.getAgentId() == null) {
                return;
            }
            List<AgentInstance> instances =
                    agentInstanceRepository.findByAgentIdAndStatus(
                            conv.getAgentId(), AgentInstance.Status.ONLINE);
            for (AgentInstance instance : instances) {
                if (connectionManager.isConnected(instance.getId())) {
                    try {
                        webSocketHandler.sendApprovalDecision(instance.getId(), payload);
                    } catch (Exception e) {
                        log.warn("Expiry frame push to instance {} failed: {}",
                                instance.getId(), e.getMessage());
                    }
                }
            }
        });
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
