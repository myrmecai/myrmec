// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.websocket.host.payload.HostCapacityPayload;
import ai.myrmec.engine.websocket.host.payload.HostHeartbeatPayload;
import ai.myrmec.engine.websocket.host.payload.HostOpenPayload;
import ai.myrmec.engine.websocket.host.payload.HostOpenedPayload;
import ai.myrmec.engine.websocket.host.payload.ProtocolErrorPayload;
import ai.myrmec.engine.websocket.host.payload.SessionAcceptPayload;
import ai.myrmec.engine.websocket.host.payload.SessionClosedPayload;
import ai.myrmec.engine.websocket.host.payload.SessionOfferPayload;
import ai.myrmec.engine.websocket.host.payload.SessionOpenedPayload;
import ai.myrmec.engine.websocket.host.payload.SessionRejectPayload;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The host-principal control socket (protocol §4.2/§6). The handshake
 * authenticated the durable host; the first valid host.open creates the
 * agent_host_instances row (the ONLY thing that does, per §4.1). Message
 * handling is boundary-validated (§14): malformed or unknown frames get a
 * protocol.error and the socket stays open.
 */
@Slf4j
@Component
public class HostControlWebSocketHandler extends TextWebSocketHandler {

    public static final String ATTR_HOST_INSTANCE_ID = "hostInstanceId";

    private final ObjectMapper objectMapper;
    private final AgentHostRepository agentHostRepository;
    private final AgentHostInstanceRepository instanceRepository;
    private final HostConnectionManager connectionManager;
    private final NodeRegistryService nodeRegistryService;
    private final SessionAllocator sessionAllocator;
    private final SessionContextAssembler sessionAssembler;
    private final SessionRepository sessionRepository;

    @Value("${myrmec.host.heartbeat-interval-seconds:15}")
    private int heartbeatIntervalSeconds;
    @Value("${myrmec.host.offer-timeout-seconds:10}")
    private int offerTimeoutSeconds;
    @Value("${myrmec.host.event-replay-window-seconds:3600}")
    private int eventReplayWindowSeconds;
    @Value("${myrmec.host.stream.max-frame-bytes:65536}")
    private int maxFrameBytes;
    @Value("${myrmec.host.stream.max-buffered-delta-bytes:262144}")
    private int maxBufferedDeltaBytes;
    @Value("${myrmec.host.stream.max-unacked-event-bytes:8388608}")
    private int maxUnackedEventBytes;
    @Value("${myrmec.host.stream.event-backpressure-timeout-seconds:30}")
    private int eventBackpressureTimeoutSeconds;
    @Value("${myrmec.host.session-idle-timeout-seconds:1800}")
    private int idleTimeoutSeconds;

    public HostControlWebSocketHandler(ObjectMapper objectMapper,
                                       AgentHostRepository agentHostRepository,
                                       AgentHostInstanceRepository instanceRepository,
                                       HostConnectionManager connectionManager,
                                       NodeRegistryService nodeRegistryService,
                                       SessionAllocator sessionAllocator,
                                       SessionContextAssembler sessionAssembler,
                                       SessionRepository sessionRepository) {
        this.objectMapper = objectMapper;
        this.agentHostRepository = agentHostRepository;
        this.instanceRepository = instanceRepository;
        this.connectionManager = connectionManager;
        this.nodeRegistryService = nodeRegistryService;
        this.sessionAllocator = sessionAllocator;
        this.sessionAssembler = sessionAssembler;
        this.sessionRepository = sessionRepository;
    }

    /** Exposed for handler tests to assert registration. */
    public HostConnectionManager getConnectionManager() {
        return connectionManager;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        final String correlationId;
        final String type;
        HostProtocolEnvelope envelope;
        try {
            envelope = HostProtocolEnvelope.parse(objectMapper, message.getPayload());
        } catch (Exception e) {
            log.warn("Malformed host frame rejected: {}", e.getMessage());
            send(session, HostProtocolEnvelope.reply(HostProtocol.PROTOCOL_ERROR, null,
                    new ProtocolErrorPayload(HostProtocol.INVALID_MESSAGE,
                            "Frame is not a valid envelope", false, null, "CONNECTION", null),
                    objectMapper));
            return;
        }
        correlationId = envelope.getMessageId();
        type = envelope.getType();

        String shapeError = envelope.validate();
        if (shapeError != null) {
            sendError(session, correlationId, shapeError, "Envelope failed boundary validation",
                    false, "CONNECTION", null);
            return;
        }

        switch (type) {
            case HostProtocol.HOST_OPEN -> handleHostOpen(session, envelope);
            case HostProtocol.HOST_HEARTBEAT -> handleHostHeartbeat(session, envelope);
            case HostProtocol.HOST_CAPACITY -> handleHostCapacity(session, envelope);
            case HostProtocol.SESSION_ACCEPT -> handleSessionAccept(session, envelope);
            case HostProtocol.SESSION_REJECT -> handleSessionReject(session, envelope);
            case HostProtocol.SESSION_OPENED -> handleSessionOpened(session, envelope);
            case HostProtocol.SESSION_CLOSED -> handleSessionClosed(session, envelope);
            case HostProtocol.SESSION_OFFER, HostProtocol.SESSION_OPEN, HostProtocol.SESSION_CLOSE ->
                    logEngineToHostIgnored(session, envelope);
            default ->
                    sendError(session, correlationId, HostProtocol.UNSUPPORTED_MESSAGE,
                            "Unknown message type: " + type, false, "CONNECTION", null);
        }
    }

    /** §5: engine->host frames echoed back are ignored — they carry no host command. */
    private void logEngineToHostIgnored(WebSocketSession session, HostProtocolEnvelope envelope) {
        log.debug("Ignoring engine->host frame {} on session {}",
                envelope.getType(), session.getId());
    }

    /** §6.1/§6.2: mint (or idempotently answer) the live supervisor run. */
    private void handleHostOpen(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID hostId = (UUID) session.getAttributes().get(HostControlHandshakeInterceptor.ATTR_HOST_ID);
        if (hostId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                    "Connection is not host-authenticated", false, "CONNECTION", null);
            return;
        }
        AgentHost host = agentHostRepository.findById(hostId).orElse(null);
        if (host == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                    "Authenticated host no longer exists", false, "CONNECTION", null);
            return;
        }

        HostOpenPayload open;
        try {
            open = objectMapper.treeToValue(envelope.getPayload(), HostOpenPayload.class);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "host.open payload failed validation: " + e.getMessage(), false, "CONNECTION", null);
            return;
        }
        if (open.instanceNonce() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "instanceNonce is required", false, "CONNECTION", null);
            return;
        }

        UUID ownerUserId = host.getHostType() == ai.myrmec.engine.agent.AgentHostType.MANAGED
                ? null : host.getOwnerUserId();
        int announced = open.poolSize() > 0 ? open.poolSize() : 1;
        int effective = Math.min(announced, host.getMaxAgents());

        // §6.1 replay-idempotency: same nonce still live -> answer, don't mint.
        Optional<AgentHostInstance> liveSameNonce = instanceRepository
                .findByAgentHostIdAndInstanceNonceAndStatus(
                        hostId, open.instanceNonce().toString(), AgentHostInstance.Status.OPEN);
        AgentHostInstance instance;
        if (liveSameNonce.isPresent()) {
            instance = liveSameNonce.get();
            log.info("host.open replay for host {} answered with existing instance {}",
                    hostId, instance.getId());
        } else {
            // New run: supersede any previous live instance of THIS host (§2.3).
            instanceRepository.findByAgentHostIdAndStatus(hostId, AgentHostInstance.Status.OPEN)
                    .forEach(previous -> {
                        previous.close("SUPERSEDED");
                        instanceRepository.save(previous);
                        log.info("Superseded live instance {} of host {}",
                                previous.getId(), hostId);
                    });

            instance = instanceRepository.saveAndFlush(AgentHostInstance.open(
                    host, ownerUserId, open.instanceNonce().toString(),
                    open.hostname(), effective, open.reportedCapacity(),
                    nodeRegistryService.getSelfNodeId()));
            log.info("host.open created instance {} for host {} (pool {})",
                    instance.getId(), hostId, effective);
        }

        session.getAttributes().put(ATTR_HOST_INSTANCE_ID, instance.getId());
        connectionManager.register(instance.getId(), session);

        send(session, HostProtocolEnvelope.reply(HostProtocol.HOST_OPENED, envelope.getMessageId(),
                new HostOpenedPayload(
                        instance.getId(),
                        HostProtocol.SUPPORTED_VERSION,
                        effective,
                        heartbeatIntervalSeconds,
                        offerTimeoutSeconds,
                        eventReplayWindowSeconds,
                        new HostOpenedPayload.StreamLimits(
                                maxFrameBytes, maxBufferedDeltaBytes,
                                maxUnackedEventBytes, eventBackpressureTimeoutSeconds),
                        nodeRegistryService.getSelfNodeId()),
                objectMapper));
    }

    /** §3.2: disconnect closes the instance row (append-only history) and
     *  unregisters the socket. The close reason is standardized. */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connectionManager.unregister(session);
        Object instanceId = session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId instanceof UUID id) {
            instanceRepository.findById(id).ifPresent(instance -> {
                instance.close(CloseStatus.NORMAL.equals(status)
                        ? "DISCONNECT" : "ABNORMAL_DISCONNECT");
                instanceRepository.save(instance);
                log.info("Host instance {} closed ({})", id, status);
            });
            session.getAttributes().remove(ATTR_HOST_INSTANCE_ID);
        }
    }

    /** §6.4: liveness signal — refreshes last_heartbeat_at only. */
    private void handleHostHeartbeat(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "host.heartbeat is not valid before host.opened", false, "CONNECTION", null);
            return;
        }
        if (envelope.getHostInstanceId() != null && !envelope.getHostInstanceId().equals(instanceId)) {
            sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                    "hostInstanceId does not match the connection", false, "CONNECTION", null);
            return;
        }
        try {
            objectMapper.treeToValue(envelope.getPayload(), HostHeartbeatPayload.class);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "host.heartbeat payload failed validation: " + e.getMessage(),
                    false, "CONNECTION", null);
            return;
        }
        instanceRepository.findById(instanceId).ifPresent(instance -> {
            instance.markHeartbeat();
            instanceRepository.save(instance);
        });
    }

    /** §6.5: capacity change — clamps the announced pool to maxAgents. */
    private void handleHostCapacity(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "host.capacity is not valid before host.opened", false, "CONNECTION", null);
            return;
        }
        if (envelope.getHostInstanceId() != null && !envelope.getHostInstanceId().equals(instanceId)) {
            sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                    "hostInstanceId does not match the connection", false, "CONNECTION", null);
            return;
        }
        HostCapacityPayload capacity;
        try {
            capacity = objectMapper.treeToValue(envelope.getPayload(), HostCapacityPayload.class);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "host.capacity payload failed validation: " + e.getMessage(),
                    false, "CONNECTION", null);
            return;
        }
        instanceRepository.findById(instanceId).ifPresent(instance -> {
            AgentHost host = agentHostRepository.findById(instance.getAgentHostId()).orElse(null);
            int ceiling = host != null ? host.getMaxAgents() : 1;
            int effective = Math.min(Math.max(1, capacity.poolSize()), ceiling);
            instance.setPoolSize(effective);
            instanceRepository.save(instance);
            log.info("Host instance {} capacity updated to {}",
                    instanceId, instance.getPoolSize());
        });
    }

    /** §7.2: host committed a local slot for the offered session. */
    private void handleSessionAccept(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "session.accept before host.opened", false, "SESSION", null);
            return;
        }
        UUID sessionId = envelope.getSessionId();
        if (sessionId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "sessionId is required", false, "SESSION", null);
            return;
        }
        try {
            var accept = objectMapper.treeToValue(envelope.getPayload(), SessionAcceptPayload.class);
            boolean ok = sessionAllocator.accept(sessionId);
            if (!ok) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "session.accept for a session not in OFFERED", false, "SESSION", null);
                return;
            }
            log.info("Session {} accepted by instance {}", sessionId, instanceId);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "session.accept payload invalid: " + e.getMessage(), false, "SESSION", null);
        }
    }

    /** §7.2: host refused the offer — release the reservation. */
    private void handleSessionReject(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "session.reject before host.opened", false, "SESSION", null);
            return;
        }
        UUID sessionId = envelope.getSessionId();
        if (sessionId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "sessionId is required", false, "SESSION", null);
            return;
        }
        try {
            var reject = objectMapper.treeToValue(envelope.getPayload(), SessionRejectPayload.class);
            sessionAllocator.reject(sessionId, reject.reasonCode(), reject.message(), reject.retryable());
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "session.reject payload invalid: " + e.getMessage(), false, "SESSION", null);
        }
    }

    /** §7.4/§19.1: context installed — mint the worker row, flip ACTIVE. */
    private void handleSessionOpened(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "session.opened before host.opened", false, "SESSION", null);
            return;
        }
        UUID sessionId = envelope.getSessionId();
        if (sessionId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "sessionId is required", false, "SESSION", null);
            return;
        }
        try {
            var opened = objectMapper.treeToValue(envelope.getPayload(), SessionOpenedPayload.class);
            boolean ok = sessionAllocator.confirmOpened(sessionId, opened.channelMode());
            if (!ok) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "session.opened for a session not in INITIALIZING", false, "SESSION", null);
                return;
            }
            log.info("Session {} opened on instance {}", sessionId, instanceId);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "session.opened payload invalid: " + e.getMessage(), false, "SESSION", null);
        }
    }

    /** §9: host confirmed cleanup — terminal close on the engine side. */
    private void handleSessionClosed(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "session.closed before host.opened", false, "SESSION", null);
            return;
        }
        UUID sessionId = envelope.getSessionId();
        if (sessionId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "sessionId is required", false, "SESSION", null);
            return;
        }
        try {
            var closed = objectMapper.treeToValue(envelope.getPayload(), SessionClosedPayload.class);
            sessionAllocator.close(sessionId, closed.reasonCode());
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "session.closed payload invalid: " + e.getMessage(), false, "SESSION", null);
        }
    }

    /**
     * §7.3: send the fully assembled session.open to the host that accepted
     * the session. Public so Plan 5's dispatch path can call it after accept.
     */
    public void sendSessionOpen(UUID sessionId, UUID agentProfileId) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        WebSocketSession socket = connectionManager.getSession(session.getHostInstanceId()).orElse(null);
        if (socket == null) {
            return;
        }
        SessionOpenPayload context = sessionAssembler.assembleContext(
                sessionId, session.getServiceType(), session.getRefId(),
                session.getProjectId(), agentProfileId);
        send(socket, HostProtocolEnvelope.reply(HostProtocol.SESSION_OPEN, null, context, objectMapper));
    }

    /** §7.1: send the offer for a reserved session (Plan 5's dispatcher calls this). */
    public void sendSessionOffer(UUID sessionId, String kind, UUID refId) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        WebSocketSession socket = connectionManager.getSession(session.getHostInstanceId()).orElse(null);
        if (socket == null) {
            return;
        }
        send(socket, HostProtocolEnvelope.reply(HostProtocol.SESSION_OFFER, null,
                new SessionOfferPayload(sessionId, sessionId, kind,
                        new SessionOfferPayload.Ref(kind, refId),
                        new SessionOfferPayload.Requirements(List.of(), List.of(), List.of()),
                        new SessionOfferPayload.Lease(session.getOfferExpiresAt(), idleTimeoutSeconds),
                        new SessionOfferPayload.Routing(nodeRegistryService.getSelfNodeId(), null)),
                objectMapper));
    }

    private void sendError(WebSocketSession session, String correlationId, String code,
                           String message, boolean retryable, String scope,
                           Map<String, Object> details) {
        send(session, HostProtocolEnvelope.reply(HostProtocol.PROTOCOL_ERROR, correlationId,
                new ProtocolErrorPayload(code, message, retryable, correlationId, scope, details),
                objectMapper));
    }

    private void send(WebSocketSession session, HostProtocolEnvelope envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            log.warn("Failed sending host frame to session {}: {}",
                    session.getId(), e.getMessage());
        }
    }
}
