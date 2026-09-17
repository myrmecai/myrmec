// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.spi.crypto.EncryptionService;
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
import ai.myrmec.engine.conversation.ConversationMessage;
import ai.myrmec.engine.conversation.ConversationService;
import ai.myrmec.engine.conversation.dispatch.PendingConversationTurns;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.inference.execution.ExecutionCommandSender;
import ai.myrmec.engine.inference.execution.ExecutionRegistry;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.websocket.host.payload.ExecutionAcceptPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionApprovalRequestedPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionCancelledPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionCompletePayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionDeltaPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionEventPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionFailedPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionPausedPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionRejectPayload;
import ai.myrmec.engine.websocket.host.payload.ProtocolAckPayload;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import ai.myrmec.engine.websocket.message.payload.MessageDeltaPayload;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import ai.myrmec.engine.inference.execution.ExecutionBridge;
import ai.myrmec.engine.workflow.ConversationEventIngestionService;
import ai.myrmec.engine.workflow.OrchestrationEventIngestionService;
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

    /** Run-key size in bytes (credential-envelope design §6). */
    private static final int PSK_BYTES = 32;

    private static final java.security.SecureRandom PSK_RANDOM = new java.security.SecureRandom();

    private final ObjectMapper objectMapper;
    private final AgentHostRepository agentHostRepository;
    private final AgentHostInstanceRepository instanceRepository;
    private final HostConnectionManager connectionManager;
    private final ChannelConnectionRegistry channelRegistry;
    private final NodeRegistryService nodeRegistryService;
    private final SessionAllocator sessionAllocator;
    private final SessionContextAssembler sessionAssembler;
    private final SessionRepository sessionRepository;
    private final ExecutionRegistry executionRegistry;
    private final ConversationStreamBroker conversationStreamBroker;
    private final ConversationService conversationService;
    private final SessionExecutionRepository executionRepository;
    private final OrchestrationEventIngestionService eventIngestionService;
    private final ConversationEventIngestionService conversationEventIngestionService;
    private final ExecutionBridge executionBridge;
    private final ai.myrmec.engine.workflow.TaskAttemptService taskAttemptService;
    private final PendingConversationTurns pendingConversationTurns;
    private final ExecutionCommandSender executionCommandSender;
    private final ai.myrmec.engine.conversation.ConversationRepository conversationRepository;
    private final ai.myrmec.engine.agent.AgentProfileVersionRepository agentProfileVersionRepository;
    private final ai.myrmec.engine.workflow.TaskDispatchContinuation taskDispatchContinuation;
    private final EncryptionService encryptionService;

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
                                       ChannelConnectionRegistry channelRegistry,
                                       NodeRegistryService nodeRegistryService,
                                       SessionAllocator sessionAllocator,
                                       SessionContextAssembler sessionAssembler,
                                       SessionRepository sessionRepository,
                                       ExecutionRegistry executionRegistry,
                                       ConversationStreamBroker conversationStreamBroker,
                                       ConversationService conversationService,
                                       SessionExecutionRepository executionRepository,
                                       OrchestrationEventIngestionService eventIngestionService,
                                       ConversationEventIngestionService conversationEventIngestionService,
                                       ExecutionBridge executionBridge,
                                       ai.myrmec.engine.workflow.TaskAttemptService taskAttemptService,
                                       PendingConversationTurns pendingConversationTurns,
                                       ExecutionCommandSender executionCommandSender,
                                       ai.myrmec.engine.conversation.ConversationRepository conversationRepository,
                                       ai.myrmec.engine.agent.AgentProfileVersionRepository agentProfileVersionRepository,
                                       @org.springframework.context.annotation.Lazy
                                       ai.myrmec.engine.workflow.TaskDispatchContinuation taskDispatchContinuation,
                                       EncryptionService encryptionService) {
        this.objectMapper = objectMapper;
        this.agentHostRepository = agentHostRepository;
        this.instanceRepository = instanceRepository;
        this.connectionManager = connectionManager;
        this.channelRegistry = channelRegistry;
        this.nodeRegistryService = nodeRegistryService;
        this.sessionAllocator = sessionAllocator;
        this.sessionAssembler = sessionAssembler;
        this.sessionRepository = sessionRepository;
        this.executionRegistry = executionRegistry;
        this.conversationStreamBroker = conversationStreamBroker;
        this.conversationService = conversationService;
        this.executionRepository = executionRepository;
        this.eventIngestionService = eventIngestionService;
        this.conversationEventIngestionService = conversationEventIngestionService;
        this.executionBridge = executionBridge;
        this.taskAttemptService = taskAttemptService;
        this.pendingConversationTurns = pendingConversationTurns;
        this.executionCommandSender = executionCommandSender;
        this.conversationRepository = conversationRepository;
        this.agentProfileVersionRepository = agentProfileVersionRepository;
        this.taskDispatchContinuation = taskDispatchContinuation;
        this.encryptionService = encryptionService;
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
            case HostProtocol.EXECUTION_ACCEPT -> handleExecutionAccept(session, envelope);
            case HostProtocol.EXECUTION_REJECT -> handleExecutionReject(session, envelope);
            case HostProtocol.EXECUTION_DELTA -> handleExecutionDelta(session, envelope);
            case HostProtocol.EXECUTION_EVENT -> handleExecutionEvent(session, envelope);
            case HostProtocol.EXECUTION_COMPLETE -> handleExecutionComplete(session, envelope);
            case HostProtocol.EXECUTION_FAILED -> handleExecutionFailed(session, envelope);
            case HostProtocol.EXECUTION_PAUSED -> handleExecutionPaused(session, envelope);
            case HostProtocol.EXECUTION_CANCELLED -> handleExecutionCancelled(session, envelope);
            case HostProtocol.EXECUTION_START, HostProtocol.EXECUTION_CANCEL,
                 HostProtocol.EXECUTION_POLICY_UPDATE ->
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
        boolean replay = liveSameNonce.isPresent();
        String pskBase64;
        UUID pskKeyId;
        AgentHostInstance instance;
        if (replay) {
            // Same-nonce replay: NO new key — re-deliver the existing one
            // (a recovery feature; credential-envelope design §6).
            instance = liveSameNonce.get();
            pskBase64 = encryptionService.decrypt(instance.getPskEncrypted());
            pskKeyId = instance.getPskKeyId();
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

            // Credential-envelope design §6: mint a fresh 32-byte PSK per run.
            // The plaintext crosses the wire exactly once (host.opened); only
            // the EncryptionService-encrypted copy persists on the row.
            byte[] psk = new byte[PSK_BYTES];
            PSK_RANDOM.nextBytes(psk);
            pskKeyId = UUID.randomUUID();
            pskBase64 = java.util.Base64.getEncoder().encodeToString(psk);
            instance.setPsk(pskKeyId, encryptionService.encrypt(pskBase64));
            instance = instanceRepository.saveAndFlush(instance);
            log.info("host.open created instance {} for host {} (pool {}, key {})",
                    instance.getId(), hostId, effective, pskKeyId);
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
                        nodeRegistryService.getSelfNodeId(),
                        pskBase64,
                        pskKeyId),
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
            // §9 (P6-T6): the host's control socket is the session's
            // lifeline — when it dies, every session the instance was
            // serving closes with HOST_LOST (dev-phase: no RECOVERING).
            // The affected conversation re-offers a fresh session on the
            // next turn; an ordinary workflow task's progression pass
            // re-dispatches (attempt stays attempt-numbered).
            try {
                sessionAllocator.closeAllOnHostInstance(id, "HOST_LOST");
            } catch (Exception e) {
                // The allocation sweep reconciles leaked rows; never let a
                // close-path failure break socket teardown.
                log.warn("Host-lost session close for instance {} failed: {}", id, e.getMessage(), e);
            }
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
            // §7.3: continue a staged turn/dispatch — send the assembled
            // session.open now that the host committed its slot.
            continueStagedTurnOnAccept(sessionId);
            taskDispatchContinuation.onSessionAccepted(sessionId);
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
            // The refusal ends this turn's staged work; the next USER turn re-offers.
            pendingConversationTurns.discard(sessionId);
            taskDispatchContinuation.onSessionEnded(sessionId);
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
            // §7.4/§8.1: the session is ACTIVE — ship the staged conversation
            // turn and/or workflow dispatch. No execution.start is legal before
            // this point.
            shipStagedTurnOnOpened(sessionId);
            taskDispatchContinuation.onSessionOpened(sessionId);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "session.opened payload invalid: " + e.getMessage(), false, "SESSION", null);
        }
    }

    /**
     * §7.3 continuation for a staged conversation turn: the host committed a
     * local slot, so send the fully assembled {@code session.open} (context
     * installed before any {@code execution.start}). The staged turn itself is
     * kept — {@code session.opened} consumes it.
     */
    private void continueStagedTurnOnAccept(UUID sessionId) {
        PendingConversationTurns.PendingTurn turn = pendingConversationTurns.peek(sessionId).orElse(null);
        if (turn == null) {
            return;
        }
        // §7.3 needs the profile the conversation pinned; the pinned version row
        // is on the conversation, addressed through the session's refId.
        UUID pinnedProfileId = sessionRepository.findById(sessionId)
                .flatMap(s -> conversationRepository.findById(s.getRefId()))
                .map(ai.myrmec.engine.conversation.Conversation::getAgentProfileVersionId)
                .flatMap(agentProfileVersionRepository::findByIdWithTools)
                .map(ai.myrmec.engine.agent.AgentProfileVersion::getProfileId)
                .orElse(null);
        sendSessionOpen(sessionId, pinnedProfileId);
        log.info("Continued staged conversation turn for session {} (conv {}) — session.open sent",
                sessionId, turn.conversationId());
    }

    /**
     * §7.4/§8.1 continuation for a staged conversation turn: the session is
     * ACTIVE, so create the execution row and ship {@code execution.start}.
     */
    private void shipStagedTurnOnOpened(UUID sessionId) {
        PendingConversationTurns.PendingTurn turn = pendingConversationTurns.take(sessionId).orElse(null);
        if (turn == null) {
            return;
        }
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("Staged turn for session {} has no session row — dropping", sessionId);
            return;
        }
        SessionExecution execution = executionRegistry.start(sessionId, turn.conversationId().toString(),
                turn.wirePayload().deadline(), turn.input()).orElse(null);
        if (execution == null) {
            log.warn("execution.start refused for staged session {} (conv {})",
                    sessionId, turn.conversationId());
            return;
        }
        boolean sent = executionCommandSender.startConversation(
                execution.getId(), session, turn.toStartPayload(execution.getId()));
        if (sent) {
            log.info("Shipped staged conversation turn for session {} (conv {}, execution {})",
                    sessionId, turn.conversationId(), execution.getId());
        } else {
            log.warn("Host socket gone for staged session {} — execution.start not delivered", sessionId);
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
            taskDispatchContinuation.onSessionEnded(sessionId);
            // A closed session can never resume its staged turn; drop it so the
            // next USER turn offers cleanly.
            pendingConversationTurns.discard(sessionId);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "session.closed payload invalid: " + e.getMessage(), false, "SESSION", null);
        }
    }

    /** §9: send session.close to the serving instance (after terminal recording). */
    public void sendSessionClose(UUID sessionId, String reasonCode) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) return;
        var socket = resolveOutboundSocket(sessionId, session.getHostInstanceId());
        if (socket.isEmpty()) return;
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(
                HostProtocol.SESSION_CLOSE, null,
                Map.of("sessionId", sessionId, "reasonCode", reasonCode, "gracePeriodSeconds", 5),
                objectMapper);
        envelope.setSessionId(sessionId);
        send(socket.get(), envelope);
    }

    /**
     * §7.5: resolve the outbound socket channel-first — the bound dedicated
     * channel when present, else the control socket. Used by every
     * engine→host frame that already knows its sessionId.
     */
    public java.util.Optional<WebSocketSession> resolveOutboundSocket(UUID sessionId, UUID hostInstanceId) {
        if (sessionId != null) {
            var channel = channelRegistry.getChannel(sessionId);
            if (channel.isPresent()) {
                return channel;
            }
        }
        return connectionManager.getSession(hostInstanceId);
    }

    /** §7.3: send the fully assembled session.open to the host that accepted
     * the session. Public so Plan 5's dispatch path can call it after accept. */
    public void sendSessionOpen(UUID sessionId, UUID agentProfileId) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        WebSocketSession socket = resolveOutboundSocket(sessionId, session.getHostInstanceId()).orElse(null);
        if (socket == null) {
            return;
        }
        send(socket, HostProtocolEnvelope.reply(HostProtocol.SESSION_OPEN, null,
                sessionAssembler.assembleContext(
                        sessionId, session.getServiceType(), session.getRefId(),
                        session.getProjectId(), agentProfileId),
                objectMapper));
    }

    /**
     * §7.3/§16.2: send the fully assembled session.open from an already-resolved
     * payload. When an ORCHESTRATOR step's assignment was installed by the
     * caller it rides this frame (§8.1's orchestration shape: the assignment is
     * installed here, and {@code execution.start} references the stored bytes by
     * dispatchId/attemptId/digest only).
     */
    public void sendSessionOpen(UUID sessionId, SessionOpenPayload context) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        WebSocketSession socket = resolveOutboundSocket(sessionId, session.getHostInstanceId()).orElse(null);
        if (socket == null) {
            return;
        }
        send(socket, HostProtocolEnvelope.reply(HostProtocol.SESSION_OPEN, null, context, objectMapper));
    }

    /** §7.1: send the offer for a reserved session (Plan 5's dispatcher calls this). */
    public boolean sendSessionOffer(UUID sessionId, String kind, UUID refId) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        // An offer precedes any channel binding by definition — control socket.
        WebSocketSession socket = connectionManager.getSession(session.getHostInstanceId()).orElse(null);
        if (socket == null) {
            return false;
        }
        send(socket, HostProtocolEnvelope.reply(HostProtocol.SESSION_OFFER, null,
                new SessionOfferPayload(sessionId, sessionId, kind,
                        new SessionOfferPayload.Ref(kind, refId),
                        new SessionOfferPayload.Requirements(List.of(), List.of(), List.of()),
                        new SessionOfferPayload.Lease(session.getOfferExpiresAt(), idleTimeoutSeconds),
                        new SessionOfferPayload.Routing(nodeRegistryService.getSelfNodeId(), null)),
                objectMapper));
        return true;
    }

    /** §8.2: host durably admitted the execution — STARTING→RUNNING. */
    private void handleExecutionAccept(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "execution.accept before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId is required", false, "EXECUTION", null);
            return;
        }
        try {
            var accept = objectMapper.treeToValue(envelope.getPayload(), ExecutionAcceptPayload.class);
            boolean ok = executionRegistry.accept(envelope.getExecutionId(),
                    accept.startedAt(), accept.resolvedModelId(),
                    accept.dispatchId(), accept.assignmentDigest());
            if (!ok) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "execution.accept for a non-STARTING execution", false, "EXECUTION", null);
            }
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.accept payload invalid: " + e.getMessage(), false, "EXECUTION", null);
        }
    }

    /** §8.2: host refused — STARTING→REJECTED. */
    private void handleExecutionReject(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "execution.reject before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId is required", false, "EXECUTION", null);
            return;
        }
        try {
            var reject = objectMapper.treeToValue(envelope.getPayload(), ExecutionRejectPayload.class);
            boolean ok = executionRegistry.reject(envelope.getExecutionId());
            if (!ok) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "execution.reject for a non-STARTING execution", false, "EXECUTION", null);
            }
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.reject payload invalid: " + e.getMessage(), false, "EXECUTION", null);
        }
    }

    /** §8.3: ephemeral delta — bridge to conversation viewers, no state, no ack. */
    private void handleExecutionDelta(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) return;
        if (envelope.getExecutionId() == null) {
            log.debug("Dropping execution.delta without executionId");
            return;
        }
        try {
            var payload = objectMapper.treeToValue(envelope.getPayload(), ExecutionDeltaPayload.class);
            SessionExecution execution = executionRepository.findById(envelope.getExecutionId()).orElse(null);
            if (execution == null) {
                log.debug("Dropping execution.delta for unknown execution {}", envelope.getExecutionId());
                return;
            }
            if (!"CONVERSATION".equals(execution.getServiceType())) {
                log.debug("Dropping execution.delta for non-conversation execution {}", envelope.getExecutionId());
                return;
            }
            ai.myrmec.engine.inference.Session sess = sessionRepository.findById(execution.getSessionId()).orElse(null);
            if (sess == null) return;
            MessageDeltaPayload legacy = new MessageDeltaPayload();
            legacy.setConversationId(sess.getRefId());
            legacy.setSequenceNo(execution.getSequenceNo() == null ? 0L : execution.getSequenceNo());
            legacy.setDeltaIndex(payload.index());
            legacy.setContent(payload.content());
            String jsonFrame = objectMapper.writeValueAsString(
                    WebSocketMessage.of(MessageType.MESSAGE_DELTA, legacy));
            conversationStreamBroker.broadcast(sess.getRefId(), jsonFrame);
        } catch (Exception e) {
            log.debug("Dropping execution.delta on parse failure: {}", e.getMessage());
        }
    }

    /** §8.4: durable event — advance cursor + bridge to the matching ingestion sink. */
    private void handleExecutionEvent(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "execution.event before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null || envelope.getSequence() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId and sequence are required", false, "EXECUTION", null);
            return;
        }
        try {
            var payload = objectMapper.treeToValue(envelope.getPayload(), ExecutionEventPayload.class);
            SessionExecution execution = executionRepository.findById(envelope.getExecutionId()).orElse(null);
            if (execution == null) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "execution.event for unknown execution", false, "EXECUTION", null);
                return;
            }
            // §12.3 at-least-once: ack ONLY after the event is durably
            // recorded — on ingestion failure the frame stays unacked and
            // the host resends. The orchestration path bridges through
            // ExecutionBridge (which catches and drops); the conversation
            // path persists with kind=CONVERSATION.
            if ("WORKFLOW".equals(execution.getServiceType()) && execution.getDispatchId() != null) {
                eventIngestionService.ingest(
                        execution.getDispatchId(), payload.eventId(),
                        envelope.getSequence().longValue(), payload.eventType(), payload.data());
            } else if ("CONVERSATION".equals(execution.getServiceType())) {
                conversationEventIngestionService.ingest(execution.getId(), payload);
            } else {
                log.debug("Ignoring execution.event for execution {} (serviceType {})",
                        envelope.getExecutionId(), execution.getServiceType());
            }
            acknowledge(session, envelope.getMessageId(), execution.getSessionId(), envelope.getSequence().longValue());
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.event payload invalid: " + e.getMessage(), false, "EXECUTION", null);
        }
    }

    /** §8.5: terminal success. */
    private void handleExecutionComplete(WebSocketSession session, HostProtocolEnvelope envelope) {
        handleTerminal(session, envelope, SessionExecution.State.COMPLETED);
    }

    /** §8.5: terminal failure. */
    private void handleExecutionFailed(WebSocketSession session, HostProtocolEnvelope envelope) {
        // A failure frame carries error, not result — the conversation
        // complete-bridge would be a no-op on it, but wiring it there is
        // semantically wrong; ExecutionBridge.onConversationFailure
        // owns the viewer notification.
        handleTerminal(session, envelope, SessionExecution.State.FAILED);
    }

    /** §8.6: terminal pause. */
    private void handleExecutionPaused(WebSocketSession session, HostProtocolEnvelope envelope) {
        handleTerminal(session, envelope, SessionExecution.State.PAUSED);
    }

    /** §8.8: terminal cancellation. */
    private void handleExecutionCancelled(WebSocketSession session, HostProtocolEnvelope envelope) {
        handleTerminal(session, envelope, SessionExecution.State.CANCELLED);
    }

    private void handleTerminal(WebSocketSession session, HostProtocolEnvelope envelope,
                                SessionExecution.State terminalState) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "execution.terminal before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId is required", false, "EXECUTION", null);
            return;
        }
        try {
            JsonNode payloadNode = envelope.getPayload();
            Map<String, Object> payloadMap = objectMapper.convertValue(payloadNode, java.util.LinkedHashMap.class);
            // §11.2: cancellation requires the CANCELLING mark before the terminal frame.
            if (terminalState == SessionExecution.State.CANCELLED) {
                executionRegistry.requestCancel(envelope.getExecutionId());
            }
            // Detect idempotent replay before the lock so we skip downstream bridges.
            boolean isReplay = executionRepository.findById(envelope.getExecutionId())
                    .map(e -> envelope.getMessageId().equals(e.getTerminalMessageId()))
                    .orElse(false);
            boolean ok = executionRegistry.terminal(envelope.getExecutionId(), terminalState,
                    envelope.getMessageId(), payloadMap);
            if (!ok) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "execution.terminal for a non-RUNNING execution or conflicting terminal messageId",
                        false, "EXECUTION", null);
                return;
            }
            SessionExecution execution = executionRepository.findById(envelope.getExecutionId()).orElseThrow();
            if (!isReplay) {
                ai.myrmec.engine.inference.Session sess = sessionRepository.findById(execution.getSessionId()).orElse(null);
                UUID conversationId = sess != null && "CONVERSATION".equals(execution.getServiceType()) ? sess.getRefId() : null;
                UUID projectId = sess != null ? sess.getProjectId() : null;
                if ("CONVERSATION".equals(execution.getServiceType())) {
                    bridgeConversationTerminal(execution, sess, terminalState, payloadNode);
                } else if ("WORKFLOW".equals(execution.getServiceType())) {
                    bridgeWorkflowTerminal(execution, terminalState, payloadNode, payloadMap,
                            conversationId, projectId);
                }
            }
            acknowledge(session, envelope.getMessageId(), execution.getSessionId(), 0L);
            if (terminalState == SessionExecution.State.PAUSED) {
                sessionAllocator.close(execution.getSessionId(), "EXECUTION_PAUSED");
                sendSessionClose(execution.getSessionId(), "EXECUTION_PAUSED");
            } else if ("WORKFLOW".equals(execution.getServiceType())) {
                // §9 legacy workflow parity: a workflow session is one-shot —
                // the task attempt is the unit of work, so the slot returns to
                // the pool as soon as the execution is terminal. Conversations
                // stay ACTIVE for the next turn (the sticky reuse path).
                sessionAllocator.close(execution.getSessionId(), "EXECUTION_TERMINAL");
                sendSessionClose(execution.getSessionId(), "EXECUTION_TERMINAL");
            }
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.terminal payload invalid: " + e.getMessage(), false, "EXECUTION", null);
        }
    }

    private void bridgeConversationTerminal(SessionExecution execution,
                                             ai.myrmec.engine.inference.Session sess,
                                             SessionExecution.State terminalState,
                                             JsonNode payloadNode) {
        UUID conversationId = sess == null ? null : sess.getRefId();
        if (conversationId == null) {
            return;
        }
        // The transcript rows the bridge writes carry the serving host as their
        // author (legacy InboundInferenceHandler resolved it the same way, from
        // the instance's agent host). The session knows its instance; resolve
        // once for both terminal kinds.
        UUID agentId = agentIdOfSession(sess);
        switch (terminalState) {
            case COMPLETED -> {
                ExecutionCompletePayload complete = objectMapper.convertValue(payloadNode, ExecutionCompletePayload.class);
                executionBridge.onConversationComplete(conversationId, sess.getProjectId(), agentId, complete);
            }
            case PAUSED -> {
                ExecutionPausedPayload paused = objectMapper.convertValue(payloadNode, ExecutionPausedPayload.class);
                executionBridge.onConversationPaused(conversationId, agentId, paused);
            }
            case FAILED -> {
                ExecutionFailedPayload failed =
                        objectMapper.convertValue(payloadNode, ExecutionFailedPayload.class);
                executionBridge.onConversationFailure(
                        conversationId, sess.getProjectId(), agentId, failed);
            }
            case CANCELLED -> {
                // No transcript row for a cancelled conversation turn.
            }
        }
    }

    /** The durable host serving a session (null when the instance is unresolvable). */
    private UUID agentIdOfSession(ai.myrmec.engine.inference.Session session) {
        if (session == null || session.getHostInstanceId() == null) {
            return null;
        }
        return instanceRepository.findById(session.getHostInstanceId())
                .map(AgentHostInstance::getAgentHostId)
                .orElse(null);
    }

    /**
     * Workflow terminal. An engine-authored {@code dispatchId} marks an
     * ORCHESTRATOR attempt (§16.2) — its outcome flows through the
     * orchestration bridge. An ordinary INFERENCE step has no dispatch identity
     * and completes through the task-attempt sink the legacy
     * {@code inference.complete} path used.
     */
    private void bridgeWorkflowTerminal(SessionExecution execution,
                                        SessionExecution.State terminalState,
                                        JsonNode payloadNode,
                                        Map<String, Object> payloadMap,
                                        UUID conversationId,
                                        UUID projectId) {
        if (execution.getDispatchId() != null) {
            bridgeOrchestrationTerminal(execution, terminalState, payloadNode, payloadMap,
                    conversationId, projectId);
            return;
        }
        UUID attemptId = attemptIdOf(execution);
        if (attemptId == null) {
            log.debug("No task/attempt for ordinary workflow execution {} — skipping bridge",
                    execution.getId());
            return;
        }
        switch (terminalState) {
            case COMPLETED -> executionBridge.onWorkflowComplete(attemptId,
                    objectMapper.convertValue(payloadNode, ExecutionCompletePayload.class));
            case FAILED -> executionBridge.onWorkflowFailure(attemptId, payloadMap);
            case CANCELLED -> executionBridge.onWorkflowCancelled(attemptId);
            case PAUSED -> log.debug(
                    "Ordinary workflow execution {} paused — no task-attempt sink",
                    execution.getId());
        }
    }

    /** The task attempt a workflow execution serves (requestId = the task id). */
    private UUID attemptIdOf(SessionExecution execution) {
        if (execution.getRequestId() == null) {
            return null;
        }
        try {
            return taskAttemptService.findCurrentAttemptId(
                    UUID.fromString(execution.getRequestId())).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void bridgeOrchestrationTerminal(SessionExecution execution,
                                              SessionExecution.State terminalState,
                                              JsonNode payloadNode,
                                              Map<String, Object> payloadMap,
                                              UUID conversationId,
                                              UUID projectId) {
        UUID dispatchId = execution.getDispatchId();
        if (dispatchId == null) {
            log.debug("No dispatchId on orchestration execution {} — skipping outcome bridge", execution.getId());
            return;
        }
        switch (terminalState) {
            case COMPLETED, FAILED, CANCELLED ->
                    executionBridge.onOrchestrationOutcome(dispatchId, execution.getId(), terminalState, payloadMap);
            case PAUSED -> {
                ExecutionPausedPayload paused = objectMapper.convertValue(payloadNode, ExecutionPausedPayload.class);
                executionBridge.onOrchestrationApprovalRequested(dispatchId, mapApprovalRequested(paused));
                executionBridge.onOrchestrationOutcome(dispatchId, execution.getId(), terminalState, payloadMap);
            }
        }
    }

    private ExecutionApprovalRequestedPayload mapApprovalRequested(ExecutionPausedPayload paused) {
        if (paused == null || paused.suspension() == null) {
            return null;
        }
        ExecutionPausedPayload.Suspension suspension = paused.suspension();
        ExecutionPausedPayload.PendingAction pending = suspension.pendingAction();
        return new ExecutionApprovalRequestedPayload(
                paused.executionId(),
                null,
                suspension.approvalRequestId(),
                pending == null ? null : new ExecutionApprovalRequestedPayload.Action(
                        pending.actionId(), pending.type(), pending.riskClass(), pending.summary(), pending.digest()),
                null,
                null,
                suspension.expiresAt());
    }

    /** §12.3: acknowledge a durable host→engine frame after its state is recorded. */
    private void acknowledge(WebSocketSession session, String messageId, UUID sessionId, long sequence) {
        long highest = executionRegistry.acknowledgeEventSequence(sessionId, sequence);
        send(session, HostProtocolEnvelope.reply(HostProtocol.PROTOCOL_ACK, messageId,
                new ProtocolAckPayload(messageId, highest, ProtocolAckPayload.STATUS_DURABLY_RECORDED),
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

    // ====================================================================
    // §7.5 dedicated channel: shared inbound arm for the channel socket
    // ====================================================================

    /**
     * §7.5/§4.2: the SAME inbound arm as the control socket for
     * {@code execution.delta} / {@code execution.event} / terminal frames.
     * The channel socket carries the exact same envelopes and, after binding,
     * the same {@code ATTR_HOST_INSTANCE_ID} identity attribute — so the
     * private handling methods behave identically. Control-only frames
     * ({@code session.*}, {@code host.*}, lifecycle) get INVALID_MESSAGE.
     */
    public void handleChannelInbound(WebSocketSession channelSocket, HostProtocolEnvelope envelope) {
        String type = envelope.getType();
        switch (type) {
            case HostProtocol.EXECUTION_DELTA -> handleExecutionDelta(channelSocket, envelope);
            case HostProtocol.EXECUTION_EVENT -> handleExecutionEvent(channelSocket, envelope);
            case HostProtocol.EXECUTION_COMPLETE -> handleExecutionComplete(channelSocket, envelope);
            case HostProtocol.EXECUTION_FAILED -> handleExecutionFailed(channelSocket, envelope);
            case HostProtocol.EXECUTION_PAUSED -> handleExecutionPaused(channelSocket, envelope);
            case HostProtocol.EXECUTION_CANCELLED -> handleExecutionCancelled(channelSocket, envelope);
            default -> sendError(channelSocket, envelope.getMessageId(),
                    HostProtocol.INVALID_MESSAGE,
                    "Frame type " + type + " is not permitted on the dedicated channel",
                    false, "CONNECTION", null);
        }
    }
}
