// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.websocket.AgentWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Durable resend-until-accept for orchestration assignments (design
 * §16.3). The transaction that creates a task attempt commits the
 * {@code orchestration_dispatches} row (canonical bytes + digest +
 * {@code PENDING}) BEFORE any WebSocket send. This relay retransmits
 * those exact bytes until a matching {@code inference.accept} flips the
 * row to {@code ACCEPTED} under a lock. Send, relay, engine, and Agent
 * crashes never create another attempt or consume a retry.
 *
 * <p>The websocket handler is {@code @Lazy}: handler → inbound
 * orchestration handler → relay → handler would otherwise be an
 * unresolvable constructor-injection cycle (the same pattern the
 * conversation inbound service uses).</p>
 */
@Service
@Slf4j
public class OrchestrationDispatchRelay {

    private final OrchestrationDispatchRepository dispatchRepository;
    private final AgentWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    public OrchestrationDispatchRelay(
            OrchestrationDispatchRepository dispatchRepository,
            @Lazy AgentWebSocketHandler webSocketHandler,
            ObjectMapper objectMapper) {
        this.dispatchRepository = dispatchRepository;
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * Record one dispatch durably: canonical assignment bytes committed
     * with the attempt-creating transaction, before any send.
     */
    @Transactional
    public OrchestrationDispatch recordDispatch(UUID dispatchId, UUID runId, UUID taskId,
                                               String canonicalJson, String digest) {
        OrchestrationDispatch dispatch = OrchestrationDispatch.builder()
                .dispatchId(dispatchId)
                .runId(runId)
                .taskId(taskId)
                .assignment(canonicalJson)
                .assignmentDigest(digest)
                .deliveryState("PENDING")
                .sendCount(0)
                .build();
        return dispatchRepository.save(dispatch);
    }

    /**
     * Send the dispatch's exact stored bytes to the instance. Bumps
     * sendCount and stamps sentAt on every successful wire write.
     * Repeated calls are idempotent-safe: the same bytes, retransmitted.
     */
    @Transactional
    public boolean sendOnce(OrchestrationDispatch dispatch, UUID agentInstanceId) {
        if (dispatch.isAccepted()) {
            return true; // already durably admitted — nothing to resend
        }
        try {
            Map<String, Object> orchestration = objectMapper.readValue(
                    dispatch.getAssignment(),
                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
            ai.myrmec.engine.websocket.message.payload.InferenceAssignPayload payload =
                    new ai.myrmec.engine.websocket.message.payload.InferenceAssignPayload(
                            dispatch.getDispatchId(),
                            null,           // sessionId — orchestration session context
                            "WORKFLOW",
                            java.util.List.of(),
                            java.util.List.of(),
                            null, 300, false,
                            new ai.myrmec.engine.websocket.message.payload
                                    .InferenceAssignPayload.ResponseRouting(0, null),
                            orchestration,
                            dispatch.getAssignmentDigest());
            boolean sent = webSocketHandler.sendInferenceAssign(agentInstanceId, payload);
            if (sent) {
                dispatch.setDeliveryState("SENT");
                dispatch.setSendCount(dispatch.getSendCount() == null
                        ? 1 : dispatch.getSendCount() + 1);
                dispatch.setSentAt(Instant.now());
                dispatchRepository.save(dispatch);
            }
            return sent;
        } catch (Exception e) {
            log.warn("Dispatch {} relay failed: {}", dispatch.getDispatchId(), e.getMessage());
            return false;
        }
    }

    /**
     * Handle {@code inference.accept}: under a dispatch row lock, a digest
     * match flips the row to ACCEPTED (idempotent — replay returns the
     * same acknowledgement); conflicting bytes fail closed.
     *
     * @return true when the acceptance is admitted (fresh or replay)
     */
    @Transactional
    public boolean accept(UUID dispatchId, String assignmentDigest) {
        OrchestrationDispatch dispatch = dispatchRepository
                .findWithLockByDispatchId(dispatchId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown dispatch: " + dispatchId));
        if (!dispatch.getAssignmentDigest().equals(assignmentDigest)) {
            log.warn("Dispatch {} accept with CONFLICTING digest — failing closed",
                    dispatchId);
            return false;
        }
        if (dispatch.isAccepted()) {
            log.info("Dispatch {} accept replay — returning the stored acknowledgement",
                    dispatchId);
            return true;
        }
        dispatch.setDeliveryState("ACCEPTED");
        dispatch.setAcceptedAt(Instant.now());
        dispatchRepository.save(dispatch);
        log.info("Dispatch {} durably accepted", dispatchId);
        return true;
    }
}