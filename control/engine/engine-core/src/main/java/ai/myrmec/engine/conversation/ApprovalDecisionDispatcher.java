package ai.myrmec.engine.conversation;

import ai.myrmec.engine.agent.AgentInstance;
import ai.myrmec.engine.agent.AgentInstanceRepository;
import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import ai.myrmec.engine.websocket.message.payload.ApprovalDecisionPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7c — pushes APPROVAL_DECISION frames over the agent WebSocket
 * after a human decides via the REST surface.
 *
 * <p>The dispatcher is intentionally best-effort: agents wake up on the
 * future their SDK is awaiting (resolved by the frame), but they don't
 * <em>have</em> to be online when the decision is made. A reconnecting
 * agent can re-fetch decided requests via the existing
 * {@code GET /api/v1/conversations/{id}/messages} endpoint when it
 * comes back. Logging records misses so an operator can see when an
 * approval was decided while the agent was offline.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ApprovalDecisionDispatcher {

    private final ConversationRepository conversationRepository;
    private final AgentInstanceRepository agentInstanceRepository;
    private final AgentConnectionManager connectionManager;
    private final AgentWebSocketHandler webSocketHandler;

    /**
     * Build and dispatch the {@code approval.decision} frame for a
     * just-decided APPROVAL_REQUEST row. {@code clientRequestId} is
     * recovered from the request row's {@code payloadJson} envelope —
     * the agent SDK stamps it under the {@code "clientRequestId"} key
     * when it calls {@code ctx.request_approval(...)}. If the field
     * isn't present the frame still goes out (the SDK can fall back to
     * keying off {@code requestMessageId}), but the SDK can't resolve
     * its pending future in the no-clientId case — log a warning so the
     * mismatch is visible during integration testing.
     */
    public void dispatch(UUID conversationId, ConversationService.ApprovalDecisionResult result) {
        Optional<Conversation> conv = conversationRepository.findById(conversationId);
        if (conv.isEmpty() || conv.get().getAgentId() == null) {
            log.debug("Conversation {} has no pinned agent — skipping approval.decision dispatch", conversationId);
            return;
        }
        UUID agentId = conv.get().getAgentId();
        List<AgentInstance> instances =
                agentInstanceRepository.findByAgentIdAndStatus(agentId, AgentInstance.Status.ONLINE);
        if (instances.isEmpty()) {
            log.info("No online instance for agent {} (conv {}) — decision will be picked up on reconnect",
                    agentId, conversationId);
            return;
        }

        ApprovalDecisionPayload payload = ApprovalDecisionPayload.builder()
                .conversationId(conversationId)
                .requestMessageId(result.request().getId())
                .clientRequestId(extractClientRequestId(result.request()))
                .decision(result.request().getApprovalStatus() == null
                        ? null
                        : result.request().getApprovalStatus().name())
                .comment(result.response() != null ? result.response().getContent() : null)
                .responseMessageId(result.response() != null ? result.response().getId() : null)
                .approverUserId(result.request().getApproverId())
                .build();

        boolean delivered = false;
        for (AgentInstance instance : instances) {
            if (connectionManager.isConnected(instance.getId())
                    && webSocketHandler.sendApprovalDecision(instance.getId(), payload)) {
                delivered = true;
                log.debug("Dispatched approval.decision to instance {} for conv {} request {}",
                        instance.getId(), conversationId, result.request().getId());
            }
        }
        if (!delivered) {
            log.info("Could not deliver approval.decision for conv {} request {} — no live instance accepted the frame",
                    conversationId, result.request().getId());
        }
    }

    private UUID extractClientRequestId(ConversationMessage request) {
        String payloadJson = request.getPayloadJson();
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        // Cheap targeted extraction — the agent SDK promises a stable
        // top-level "clientRequestId":"<uuid>" key. Parsing the whole
        // JSON would be honest but the volume here is one-per-decision
        // and the format is fixed.
        String key = "\"clientRequestId\"";
        int idx = payloadJson.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int colon = payloadJson.indexOf(':', idx + key.length());
        if (colon < 0) {
            return null;
        }
        int firstQuote = payloadJson.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return null;
        }
        int closingQuote = payloadJson.indexOf('"', firstQuote + 1);
        if (closingQuote < 0) {
            return null;
        }
        String raw = payloadJson.substring(firstQuote + 1, closingQuote);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            log.debug("Approval request {} has clientRequestId that is not a UUID: {}",
                    request.getId(), raw);
            return null;
        }
    }
}
