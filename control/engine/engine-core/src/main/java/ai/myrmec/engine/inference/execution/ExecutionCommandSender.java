// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.node.HostFrameRelayService;
import ai.myrmec.engine.websocket.host.HostProtocol;
import ai.myrmec.engine.websocket.host.HostProtocolEnvelope;
import ai.myrmec.engine.websocket.host.payload.ExecutionCancelPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload;
import ai.myrmec.engine.websocket.host.payload.OrchestrationExecutionStartPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Engine→host execution commands (§8.1/§8.8) over the host socket — local or
 * relayed (§20, decision H5): the frame is serialized once and handed to
 * {@link HostFrameRelayService}, which delivers it to the owning replica's
 * socket directly when the instance is homed here, or relays it over the HTTP
 * mesh when the instance lives on a peer (falling back to a local socket if
 * one exists, per the relay's failure semantics).
 */
@Component
@RequiredArgsConstructor
public class ExecutionCommandSender {

    private final HostFrameRelayService relayService;
    private final SessionRepository sessionRepository;
    private final ObjectMapper objectMapper;

    @Value("${myrmec.host.execution-start-timeout-seconds:300}")
    private int executionStartTimeoutSeconds;

    /** §8.1: send execution.start for a conversation turn. */
    public boolean startConversation(UUID executionId, Session session,
                                     ExecutionStartPayload payload) {
        return send(session.getHostInstanceId(), HostProtocol.EXECUTION_START,
                null, payload, executionId, session.getId());
    }

    /**
     * §8.1: send execution.start for an ordinary (non-orchestration) workflow
     * step — the same §8.1 input-block shape as a conversation turn, shipped on
     * the WORKFLOW session the allocator gave the task.
     */
    public boolean startExecution(UUID executionId, Session session,
                                  ExecutionStartPayload payload) {
        return send(session.getHostInstanceId(), HostProtocol.EXECUTION_START,
                null, payload, executionId, session.getId());
    }

    /** §8.1: send execution.start for an orchestration attempt (§16.2 shape). */
    public boolean startOrchestration(UUID executionId, Session session,
                                      OrchestrationExecutionStartPayload payload) {
        return send(session.getHostInstanceId(), HostProtocol.EXECUTION_START,
                null, payload, executionId, session.getId());
    }

    /** §8.8: request cancellation. */
    public boolean cancel(UUID executionId, Session session, UUID dispatchId,
                          String reasonCode, int gracePeriodSeconds) {
        return send(session.getHostInstanceId(), HostProtocol.EXECUTION_CANCEL,
                null, new ExecutionCancelPayload(executionId, dispatchId, reasonCode,
                        java.time.Instant.now(), gracePeriodSeconds),
                executionId, session.getId());
    }

    /**
     * Serialize once, then route: node-aware resolution inside the relay
     * service decides local socket vs HTTP relay to the owning replica.
     */
    private boolean send(UUID hostInstanceId, String type, String correlationId,
                         Object payload, UUID executionId, UUID sessionId) {
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(type, correlationId, payload, objectMapper);
        envelope.setExecutionId(executionId);
        envelope.setSessionId(sessionId);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            return relayService.send(sessionId, hostInstanceId, json);
        } catch (Exception e) {
            return false;
        }
    }
}