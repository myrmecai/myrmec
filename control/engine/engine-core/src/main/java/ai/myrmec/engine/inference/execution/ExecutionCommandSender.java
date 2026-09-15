// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution;

import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.websocket.host.HostConnectionManager;
import ai.myrmec.engine.websocket.host.HostProtocol;
import ai.myrmec.engine.websocket.host.HostProtocolEnvelope;
import ai.myrmec.engine.websocket.host.payload.ExecutionCancelPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload;
import ai.myrmec.engine.websocket.host.payload.OrchestrationExecutionStartPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Engine→host execution commands over the host control socket (§8.1/§8.8).
 * Plan 8's dispatch cutover calls these after allocation; until then they
 * are exercised by the contract tests only.
 */
@Component
@RequiredArgsConstructor
public class ExecutionCommandSender {

    private final HostConnectionManager connectionManager;
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

    private boolean send(UUID hostInstanceId, String type, String correlationId,
                         Object payload, UUID executionId, UUID sessionId) {
        var socket = connectionManager.getSession(hostInstanceId);
        if (socket.isEmpty()) {
            return false;
        }
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(type, correlationId, payload, objectMapper);
        envelope.setExecutionId(executionId);
        envelope.setSessionId(sessionId);
        try {
            socket.get().sendMessage(new org.springframework.web.socket.TextMessage(
                    objectMapper.writeValueAsString(envelope)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
