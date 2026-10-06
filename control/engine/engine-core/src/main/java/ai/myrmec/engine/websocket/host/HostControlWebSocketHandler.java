// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.agent.AgentHostType;
import ai.myrmec.engine.spi.crypto.EncryptionService;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.node.HostFrameRelayService;
import ai.myrmec.engine.node.NodeRegistryService;
import ai.myrmec.engine.websocket.host.payload.HostCapacityPayload;
import ai.myrmec.engine.websocket.host.payload.HostHeartbeatPayload;
import ai.myrmec.engine.websocket.host.payload.HostOpenPayload;
import ai.myrmec.engine.websocket.host.payload.HostOpenedPayload;
import ai.myrmec.engine.websocket.host.payload.HostResumePayload;
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
import ai.myrmec.engine.websocket.host.payload.ExecutionControlStatePayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionDeltaPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionEventPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionFailedPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionDeltaPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload;
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
import java.time.Instant;
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
    private final HostFrameRelayService frameRelayService;
    private final ai.myrmec.engine.conversation.ConversationRepository conversationRepository;
    private final ai.myrmec.engine.agent.AgentProfileVersionRepository agentProfileVersionRepository;
    private final ai.myrmec.engine.workflow.TaskDispatchContinuation taskDispatchContinuation;
    private final EncryptionService encryptionService;
    private final HostResumeReconcileService resumeReconcileService;
    private final ai.myrmec.engine.workflow.OrchestrationDispatchRepository dispatchRepository;
    private final ai.myrmec.engine.inference.execution.interaction.ExecutionControlService executionControlService;
    private final ai.myrmec.engine.inference.execution.interaction.ExecutionInteractionService interactionService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

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
                                       HostFrameRelayService frameRelayService,
                                       ai.myrmec.engine.conversation.ConversationRepository conversationRepository,
                                       ai.myrmec.engine.agent.AgentProfileVersionRepository agentProfileVersionRepository,
                                       @org.springframework.context.annotation.Lazy
                                       ai.myrmec.engine.workflow.TaskDispatchContinuation taskDispatchContinuation,
                                       EncryptionService encryptionService,
                                       HostResumeReconcileService resumeReconcileService,
                                       ai.myrmec.engine.workflow.OrchestrationDispatchRepository dispatchRepository,
                                       @org.springframework.context.annotation.Lazy
                                       ai.myrmec.engine.inference.execution.interaction.ExecutionControlService executionControlService,
                                       @org.springframework.context.annotation.Lazy
                                       ai.myrmec.engine.inference.execution.interaction.ExecutionInteractionService interactionService,
                                       org.springframework.transaction.PlatformTransactionManager transactionManager) {
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
        this.frameRelayService = frameRelayService;
        this.conversationRepository = conversationRepository;
        this.agentProfileVersionRepository = agentProfileVersionRepository;
        this.taskDispatchContinuation = taskDispatchContinuation;
        this.encryptionService = encryptionService;
        this.resumeReconcileService = resumeReconcileService;
        this.dispatchRepository = dispatchRepository;
        this.executionControlService = executionControlService;
        this.interactionService = interactionService;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
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
            case HostProtocol.HOST_RESUME -> handleHostResume(session, envelope);
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
            // §22.7 (Task 6): the host's chat tool proposes an engine-
            // authorized control; the disposition resolves through
            // ExecutionControlService.propose (actor derived from the
            // persisted interaction).
            case HostProtocol.EXECUTION_CONTROL_REQUEST ->
                    handleExecutionControlRequest(session, envelope);
            // §5/§8.7: the host publishes an approval request BEFORE the
            // terminal execution.paused — durable receipt acknowledged (§12.3).
            case HostProtocol.EXECUTION_APPROVAL_REQUESTED ->
                    handleExecutionApprovalRequested(session, envelope);
            // §22.3 (Task 8): the interaction outcome/observed-state family.
            // Durable complete/failed settle the interaction rows (ack after
            // commit §12.3); delta is ephemeral best-effort; control.state
            // projects onto the 035 observed-state columns.
            case HostProtocol.EXECUTION_INTERACTION_COMPLETE ->
                    handleExecutionInteractionComplete(session, envelope);
            case HostProtocol.EXECUTION_INTERACTION_FAILED ->
                    handleExecutionInteractionFailed(session, envelope);
            case HostProtocol.EXECUTION_INTERACTION_DELTA ->
                    handleExecutionInteractionDelta(session, envelope);
            case HostProtocol.EXECUTION_CONTROL_STATE ->
                    handleExecutionControlState(session, envelope);
            case HostProtocol.EXECUTION_START, HostProtocol.EXECUTION_CANCEL ->
                    logEngineToHostIgnored(session, envelope);
            // §8.7 (A4): execution.policy.update is an ENGINE→host frame — a
            // host-authored copy is a protocol violation, NOT a silent ignore
            // (the host must never control its own allowances).
            case HostProtocol.EXECUTION_POLICY_UPDATE ->
                    sendError(session, correlationId, HostProtocol.INVALID_MESSAGE,
                            "execution.policy.update is engine→host only; a host may not send it",
                            false, "EXECUTION", null);
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

        // section 22.2 current-contract validation: the sessionInteraction
        // capability is REQUIRED — missing or unsupported blocks fail host
        // initialization with protocol.error and mint no instance row. This
        // is the cutover contract, not an old-host fallback.
        ai.myrmec.engine.websocket.host.payload.SessionInteractionCapability
                sessionInteraction;
        try {
            sessionInteraction = open.sessionInteractionCapability();
        } catch (IllegalArgumentException e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    e.getMessage(), false, "CONNECTION", null);
            return;
        }

        // Local-owner model (§3.7/§4.1): agent_hosts.owner_user_id is gone —
        // the ONLY owner record is agent_host_instances.owner_user_id, seeded
        // from the additive host.open payload field (the HOST_JWT carries no
        // user identity, §18 token isolation). Validation matrix:
        //   LOCAL  + ownerUserId        → instance stamped with the owner;
        //   LOCAL  + ownerUserId == null → allowed (owner stays null), but
        //         logged — the VS Code plugin always sends it;
        //   MANAGED + ownerUserId != null → protocol violation (a headless
        //         supervisor must never claim an owner).
        boolean localHost = host.getHostType() != AgentHostType.MANAGED;
        UUID ownerUserId = open.ownerUserId();
        if (!localHost && ownerUserId != null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "MANAGED hosts do not carry an owner", false, "CONNECTION", null);
            return;
        }
        if (localHost && ownerUserId == null) {
            log.debug("host.open for LOCAL host {} carries no ownerUserId — "
                    + "instance owner stays null", hostId);
        }
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
            // §20 (A3): the socket now lives on THIS replica — re-stamp the
            // routing node so peer relays target the current owner.
            instance.rehomeTo(nodeRegistryService.getSelfNodeId());
            instance = instanceRepository.saveAndFlush(instance);
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
        connectionManager.register(instance.getId(), session, Map.of(
                "sessionInteraction", sessionInteraction));

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
                        pskKeyId,
                        new HostOpenedPayload.AcceptedCapabilities(sessionInteraction)),
                objectMapper));
    }

    /**
     * §13 (A2): a reconnected host reports retained sessions; the engine
     * answers with authoritative keep/cancel/close decisions. Match by
     * (previousInstanceId, instanceNonce) — §6.1 nonce identity. No
     * RECOVERING instance (window expired, never armed, or nonce mismatch)
     * → a §13-shaped error with the reason; the host falls back to
     * {@code host.open} (fresh instance), today's path.
     */
    private void handleHostResume(WebSocketSession session, HostProtocolEnvelope envelope) {
        UUID hostId = (UUID) session.getAttributes().get(HostControlHandshakeInterceptor.ATTR_HOST_ID);
        if (hostId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                    "Connection is not host-authenticated", false, "CONNECTION", null);
            return;
        }
        HostResumePayload resume;
        try {
            resume = objectMapper.treeToValue(envelope.getPayload(), HostResumePayload.class);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "host.resume payload failed validation: " + e.getMessage(),
                    false, "CONNECTION", null);
            return;
        }

        var match = resumeReconcileService.match(
                hostId, resume.previousHostInstanceId(), resume.instanceNonce());
        if (!(match instanceof HostResumeReconcileService.MatchResult.Matched matched)) {
            String code = match instanceof HostResumeReconcileService.MatchResult.Expired
                    ? HostProtocol.SESSION_NOT_FOUND
                    : match instanceof HostResumeReconcileService.MatchResult.NonceMismatch
                        ? HostProtocol.IDENTITY_MISMATCH
                        : HostProtocol.SESSION_NOT_FOUND;
            sendError(session, envelope.getMessageId(), code,
                    "host.resume cannot re-adopt an instance — use host.open",
                    false, "CONNECTION", null);
            log.info("host.resume from host {} rejected (no RECOVERING match for {})",
                    hostId, resume.previousHostInstanceId());
            return;
        }

        AgentHostInstance instance = matched.instance();
        List<HostResumePayload.ReconcilePayload.Decision> decisions =
                resumeReconcileService.reconcile(instance, resume.sessions());

        // The re-adopted instance is this socket's identity from here on.
        session.getAttributes().put(ATTR_HOST_INSTANCE_ID, instance.getId());
        connectionManager.register(instance.getId(), session);

        HostProtocolEnvelope reply = HostProtocolEnvelope.reply(HostProtocol.HOST_RECONCILE,
                envelope.getMessageId(),
                new HostResumePayload.ReconcilePayload(instance.getId(), decisions),
                objectMapper);
        reply.setHostInstanceId(instance.getId());
        send(session, reply);
        log.info("host.resume from host {} re-adopted instance {} ({} decisions)",
                hostId, instance.getId(), decisions.size());
    }

    /** §3.2: disconnect parks the instance in the bounded RECOVERING window
     *  (§13, A2) and unregisters the socket. Retention expiry (the
     *  allocation sweep's {@code expireRecoveredInstances}) later applies
     *  the §9 fallback: close the instance + its sessions with HOST_LOST. */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connectionManager.unregister(session);
        Object instanceId = session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId instanceof UUID id) {
            String dropReason = CloseStatus.NORMAL.equals(status)
                    ? "DISCONNECT" : "ABNORMAL_DISCONNECT";
            instanceRepository.findById(id).ifPresent(instance -> {
                instance.startRecovery(sessionAllocator.recoveryRetainFor());
                instanceRepository.save(instance);
                log.info("Host instance {} dropped ({}) — RECOVERING until {}",
                        id, dropReason, instance.getRecoveryExpiresAt());
            });
            // §13 (A2): the host's control socket is the session's lifeline,
            // but a disconnect no longer fails everything — the sessions stay
            // parked (allocation-state untouched) pending host.resume; the
            // retention sweep closes them HOST_LOST if the window lapses.
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

    /** §7.4/§19.1: context installed — mint the Agent row, flip ACTIVE. */
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
            // §12.2: the session just took work — restart its idle lease from
            // now, after the send (a failed send leaves the session no busier
            // than before, so only success re-arms the window).
            sessionAllocator.touchIdleLease(sessionId);
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
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(
                HostProtocol.SESSION_CLOSE, null,
                Map.of("sessionId", sessionId, "reasonCode", reasonCode, "gracePeriodSeconds", 5),
                objectMapper);
        envelope.setSessionId(sessionId);
        relayOrSend(sessionId, session.getHostInstanceId(), envelope);
    }

    /**
     * §7.5/§22.3: route a fully-built engine→host SESSION frame
     * (session.open / session.close) to the instance's owning replica
     * channel-first with control-socket delivery — the same §7.5 routing the
     * other session frames use. These are admission/lifecycle frames: they
     * MUST reach the host before any channel binding exists (the channel
     * offer token rides IN session.open), so dedicated-only delivery cannot
     * apply here. Dedicated-only (§22.3/D7) applies to the §22 interaction
     * messages once a channel is bound (sent via
     * {@code frameRelayService.sendDedicated}).
     */
    private void relayOrSend(UUID sessionId, UUID hostInstanceId, HostProtocolEnvelope envelope) {
        envelope.setSessionId(sessionId);
        try {
            String json = objectMapper.writeValueAsString(envelope);
            boolean delivered = frameRelayService.send(sessionId, hostInstanceId, json);
            if (!delivered) {
                log.warn("Session frame {} undeliverable for session {} (instance {}) — "
                        + "engine failure/retry classification applies",
                        envelope.getType(), sessionId, hostInstanceId);
            }
        } catch (IOException e) {
            log.warn("Failed sending host frame to instance {}: {}", hostInstanceId, e.getMessage());
        }
    }

    /** §7.3: send the fully assembled session.open to the host that accepted
     * the session. Public so Plan 5's dispatch path can call it after accept. */
    public void sendSessionOpen(UUID sessionId, UUID agentProfileId) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(HostProtocol.SESSION_OPEN, null,
                sessionAssembler.assembleContext(
                        sessionId, session.getServiceType(), session.getRefId(),
                        session.getProjectId(), agentProfileId),
                objectMapper);
        relayOrSend(sessionId, session.getHostInstanceId(), envelope);
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
        relayOrSend(sessionId, session.getHostInstanceId(),
                HostProtocolEnvelope.reply(HostProtocol.SESSION_OPEN, null, context, objectMapper));
    }

    /** §7.1: send the offer for a reserved session (Plan 5's dispatcher calls this). */
    public boolean sendSessionOffer(UUID sessionId, String kind, UUID refId) {
        ai.myrmec.engine.inference.Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return false;
        }
        // An offer precedes any channel binding by definition — control socket.
        HostProtocolEnvelope envelope = HostProtocolEnvelope.reply(HostProtocol.SESSION_OFFER, null,
                new SessionOfferPayload(sessionId, sessionId, kind,
                        new SessionOfferPayload.Ref(kind, refId),
                        new SessionOfferPayload.Requirements(List.of(), List.of(), List.of()),
                        new SessionOfferPayload.Lease(session.getOfferExpiresAt(), idleTimeoutSeconds),
                        new SessionOfferPayload.Routing(nodeRegistryService.getSelfNodeId(), null)),
                objectMapper);
        try {
            return frameRelayService.sendControl(session.getHostInstanceId(),
                    objectMapper.writeValueAsString(envelope));
        } catch (IOException e) {
            log.warn("Failed sending session.offer to instance {}: {}",
                    session.getHostInstanceId(), e.getMessage());
            return false;
        }
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
            } else if (accept.dispatchId() != null) {
                // Unified session execution touchpoint 1 (design 12): the
                // orchestration dispatch row stops retransmitting the moment
                // the host durably admits the execution - the flip is
                // best-effort and must never fail the accept itself.
                flipDispatchAccepted(accept.dispatchId());
            }
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.accept payload invalid: " + e.getMessage(), false, "EXECUTION", null);
        }
    }

    /**
     * Unified session execution (design 12 touchpoint 1): an
     * {@code execution.accept} echoing a non-null {@code dispatchId}
     * (protocol 8.2 ORCHESTRATION variant) flips the
     * {@code OrchestrationDispatch} row {@code PENDING/SENT -> ACCEPTED},
     * ending the relay's retransmit. Best-effort under its own transaction
     * (a {@code TransactionTemplate} call - direct invocation would bypass
     * the proxy): a dispatch-row failure is logged, never propagated - the
     * accept has already durably succeeded.
     */
    private void flipDispatchAccepted(UUID dispatchId) {
        try {
            transactionTemplate.executeWithoutResult(tx ->
                    dispatchRepository.findWithLockByDispatchId(dispatchId)
                            .ifPresent(d -> {
                                d.setDeliveryState("ACCEPTED");
                                d.setAcceptedAt(Instant.now());
                                dispatchRepository.save(d);
                            }));
        } catch (Exception e) {
            log.warn("Dispatch accept flip failed for dispatchId {}: {}", dispatchId, e.getMessage());
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

    /**
     * §5/§8.7: the host publishes an approval request before its terminal
     * {@code execution.paused}. Orchestration approvals resolve the task from
     * the execution's engine-authored {@code dispatchId} and persist on the
     * WorkflowTask (ExecutionApprovalService); conversation approvals (no
     * {@code dispatchId}) persist through the same transcript seam the paused
     * arm uses — {@code ConversationService.appendApprovalRequest} — carrying
     * the §8.7 normalized pending action in the payload marker. Durable frame:
     * acknowledged after persist (§12.3).
     */
    private void handleExecutionApprovalRequested(WebSocketSession session,
                                                  HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "execution.approval.requested before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId is required", false, "EXECUTION", null);
            return;
        }
        try {
            var payload = objectMapper.treeToValue(envelope.getPayload(),
                    ExecutionApprovalRequestedPayload.class);
            SessionExecution execution = executionRepository.findById(envelope.getExecutionId())
                    .orElse(null);
            if (execution == null) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "execution.approval.requested for unknown execution", false, "EXECUTION", null);
                return;
            }
            // §14 correlation: the execution must belong to the authenticated
            // connection's session (mirrors handleExecutionEvent's identity walk).
            if (!instanceId.equals(executionSessionInstanceId(execution))) {
                sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                        "execution does not belong to this connection's session", false,
                        "EXECUTION", null);
                return;
            }
            if ("WORKFLOW".equals(execution.getServiceType())) {
                if (execution.getDispatchId() == null) {
                    log.debug("Ordinary workflow execution {} approval.requested — no task sink",
                            execution.getId());
                } else {
                    executionBridge.onOrchestrationApprovalRequested(
                            execution.getDispatchId(), payload);
                }
            } else if ("CONVERSATION".equals(execution.getServiceType())) {
                executionBridge.onConversationApprovalRequested(
                        execution.getSessionId(), agentIdOfExecution(execution), payload);
            } else {
                log.debug("Ignoring execution.approval.requested for execution {} (serviceType {})",
                        execution.getId(), execution.getServiceType());
            }
            acknowledge(session, envelope.getMessageId(), execution.getSessionId(), 0L);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.approval.requested payload invalid: " + e.getMessage(),
                    false, "EXECUTION", null);
        }
    }

    /** The host instance id of the session an execution rode (null when unresolvable). */
    private UUID executionSessionInstanceId(SessionExecution execution) {
        return sessionRepository.findById(execution.getSessionId())
                .map(ai.myrmec.engine.inference.Session::getHostInstanceId)
                .orElse(null);
    }

    /**
     * §22.7 (Task 6, plan 2026-10-03-session-interaction): the host's chat
     * tool proposes an engine-authorized control. The engine resolves the
     * execution's OWNERSHIP CHAIN (plan section 4 — project → workflow →
     * request → task → attempt → execution) itself, derives the actor from
     * the persisted interaction (a host-supplied actor is impossible by
     * payload shape), and resolves the disposition transactionally. The
     * resolved `execution.control.request.resolved` disposition is the
     * durable reply; the frame is acknowledged after commit (§12.3).
     */
    private void handleExecutionControlRequest(WebSocketSession session,
                                                HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "execution.control.request before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId is required", false, "EXECUTION", null);
            return;
        }
        try {
            ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload payload =
                    objectMapper.treeToValue(envelope.getPayload(),
                            ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload.class);
            SessionExecution execution = executionRepository.findById(
                    envelope.getExecutionId()).orElse(null);
            if (execution == null) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "execution.control.request for unknown execution", false, "EXECUTION", null);
                return;
            }
            if (!instanceId.equals(executionSessionInstanceId(execution))) {
                sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                        "execution does not belong to this connection's session", false,
                        "EXECUTION", null);
                return;
            }
            var scope = executionControlService.scopeForExecution(execution.getId());
            var receipt = executionControlService.propose(scope, payload);
            // §22.7: the resolved disposition is the durable reply.
            send(session, HostProtocolEnvelope.reply(
                    HostProtocol.EXECUTION_CONTROL_REQUEST_RESOLVED,
                    envelope.getMessageId(),
                    resolvedPayload(execution, payload, receipt), objectMapper));
            acknowledge(session, envelope.getMessageId(), execution.getSessionId(), 0L);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.control.request payload invalid: " + e.getMessage(),
                    false, "EXECUTION", null);
        }
    }

    /** Map the ProposalReceipt onto the §22.7 resolved payload shape. */
    private ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload
            resolvedPayload(SessionExecution execution,
                            ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload proposal,
                            ai.myrmec.engine.inference.execution.interaction.ProposalReceipt receipt) {
        return new ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload(
                execution.getId(),
                execution.getDispatchId(),
                proposal.interactionId(),
                receipt.controlRequestId(),
                receipt.resolutionRevision(),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload
                        .Status.valueOf(receipt.status()),
                receipt.expiresAt(),
                receipt.commandMessageId(),
                receipt.controlRevision(),
                receipt.errorCode() == null ? null
                        : ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload
                        .ErrorCode.valueOf(receipt.errorCode()));
    }

    // ====================================================================
    // §22.3 (Task 8): the interaction family — durable complete/failed
    // outcomes, the ephemeral delta, and the observed control state.
    // ====================================================================

    /**
     * §22.3/§22.6: the durable {@code execution.interaction.complete}
     * outcome. Identity walks EXACTLY like the other execution arms (the
     * §14 correlation: connection instance == the execution's session
     * instance; payload executionId == envelope.executionId == the loaded
     * execution; payload.dispatchId == execution.dispatchId) plus the
     * §22.2 negotiated sessionInteraction capability (fail-closed
     * UNSUPPORTED_MESSAGE). The service settles the row + usage; the
     * protocol.ack goes AFTER the service transaction commits (§12.3).
     */
    private void handleExecutionInteractionComplete(WebSocketSession session,
                                                    HostProtocolEnvelope envelope) {
        settleOutcomeArm(session, envelope, ExecutionInteractionCompletePayload.class,
                HostProtocol.EXECUTION_INTERACTION_COMPLETE, (execution, payload) ->
                        interactionService.complete(
                                executionControlService.scopeForExecution(execution.getId()),
                                envelope.getMessageId(),
                                (ExecutionInteractionCompletePayload) payload));
    }

    /** §22.3/§22.6: the durable {@code execution.interaction.failed} outcome. */
    private void handleExecutionInteractionFailed(WebSocketSession session,
                                                  HostProtocolEnvelope envelope) {
        settleOutcomeArm(session, envelope, ExecutionInteractionFailedPayload.class,
                HostProtocol.EXECUTION_INTERACTION_FAILED, (execution, payload) ->
                        interactionService.fail(
                                executionControlService.scopeForExecution(execution.getId()),
                                envelope.getMessageId(),
                                (ExecutionInteractionFailedPayload) payload));
    }

    /**
     * §22.3/§22.6: the ephemeral best-effort delta. Identity check ONLY —
     * no durable record, no ack (deltas are never acknowledged durably).
     * A failing identity is silently dropped for the connection's safety
     * (§22.3: deltas carry no durability semantics; replay resolves through
     * the durable complete). Logged at debug.
     */
    private void handleExecutionInteractionDelta(WebSocketSession session,
                                                 HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null || envelope.getExecutionId() == null) {
            log.debug("Dropping execution.interaction.delta (unbound connection or no executionId)");
            return;
        }
        if (!advertisesSessionInteraction(instanceId)) {
            log.debug("Dropping execution.interaction.delta for instance {} — no negotiated "
                    + "sessionInteraction capability", instanceId);
            return;
        }
        try {
            ExecutionInteractionDeltaPayload payload = objectMapper.treeToValue(
                    envelope.getPayload(), ExecutionInteractionDeltaPayload.class);
            SessionExecution execution = executionRepository.findById(
                    envelope.getExecutionId()).orElse(null);
            if (execution == null
                    // §22.3 identity (control-arm equivalent — silent drop:
                    // the delta itself carries no durable semantics).
                    || !payload.executionId().equals(execution.getId())
                    || !payload.dispatchId().equals(execution.getDispatchId())) {
                log.debug("Dropping execution.interaction.delta (unknown execution or "
                        + "dispatch identity mismatch) for execution {}", envelope.getExecutionId());
                return;
            }
            // §22.3: the delta binds to the session the execution serves.
            if (!instanceId.equals(executionSessionInstanceId(execution))) {
                log.debug("Dropping execution.interaction.delta for execution {} — instance "
                        + "correlation mismatch", execution.getId());
                return;
            }
            // Ephemeral: consumed in-memory by Task 9's stream broker; the
            // engine records nothing. The COMPLETE record replaces the
            // provisional deltas (§22.6).
            log.debug("execution.interaction.delta {} index {} for interaction {} "
                    + "(ephemeral, no durable record)", envelope.getMessageId(),
                    payload.index(), payload.interactionId());
        } catch (Exception e) {
            // Malformed deltas are best-effort dropped — never a protocol
            // error (the durable complete is the authority; §22.6).
            log.debug("Dropping malformed execution.interaction.delta: {}", e.getMessage());
        }
    }

    /**
     * §22.3/§22.4: the host's durable observed-state report. Identity walks
     * like the other execution arms (IDENTITY_MISMATCH on mismatch); the
     * projection onto the 035 observed-state columns runs UNDER THE
     * EXECUTION ROW LOCK with the highest-{@code stateSequence}-wins gate
     * (a stale lower sequence is a logged no-op). Ack after commit (§12.3).
     */
    private void handleExecutionControlState(WebSocketSession session,
                                             HostProtocolEnvelope envelope) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    "execution.control.state before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId is required", false, "EXECUTION", null);
            return;
        }
        if (!advertisesSessionInteraction(instanceId)) {
            sendError(session, envelope.getMessageId(), HostProtocol.UNSUPPORTED_MESSAGE,
                    "the connection did not negotiate the sessionInteraction capability (§22.2)",
                    false, "EXECUTION", null);
            return;
        }
        try {
            ExecutionControlStatePayload payload = objectMapper.treeToValue(
                    envelope.getPayload(), ExecutionControlStatePayload.class);
            SessionExecution execution = executionRepository.findById(
                    envelope.getExecutionId()).orElse(null);
            if (execution == null) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "execution.control.state for unknown execution", false,
                        "EXECUTION", null);
                return;
            }
            // §22.3 identity walk (the other execution arms' pattern).
            if (!instanceId.equals(executionSessionInstanceId(execution))) {
                sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                        "execution does not belong to this connection's session", false,
                        "EXECUTION", null);
                return;
            }
            if (!payload.executionId().equals(execution.getId())
                    || !executionIdsMatchDispatch(execution, payload)) {
                sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                        "state report identity does not match the admitted execution",
                        false, "EXECUTION", null);
                return;
            }
            // The projection commits in its OWN transaction; the ack rides
            // ONLY after that commit (§12.3).
            Boolean projected = transactionTemplate.execute(tx ->
                    projectControlState(payload));
            if (!Boolean.TRUE.equals(projected)) {
                return;   // unknown execution gone (concurrent) — nothing to ack
            }
            acknowledge(session, envelope.getMessageId(), execution.getSessionId(), 0L);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "execution.control.state payload invalid: " + e.getMessage(),
                    false, "EXECUTION", null);
        }
    }

    /**
     * The control.state projection, transactional (an own transaction via
     * the TransactionTemplate — the handler method itself is not
     * {@code @Transactional}). Returns true when the projection settled
     * (durable write OR designed stale no-op — the caller acks); false when
     * the execution vanished concurrently.
     *
     * <p>Column mapping (§22.4 → 035): {@code controlStateSequence} takes
     * the payload's stateSequence — highest wins, a stale lower sequence is
     * a logged no-op; {@code acceptedControlRevision} advances to the
     * payload's {@code controlRevision} ONLY on non-REJECTED statuses (a
     * REJECTED report carries {@code rejectedControlRevision} — the
     * accepted revision never advances from a rejection); {@code holdState}
     * = effectiveState; {@code holdChangedAt} = changedAt;
     * {@code idleResumeAt} = idleResumeAt.</p>
     */
    private boolean projectControlState(ExecutionControlStatePayload payload) {
        SessionExecution locked = executionRepository
                .findWithLockById(payload.executionId()).orElse(null);
        if (locked == null) {
            log.debug("execution.control.state for vanished execution {} — no projection",
                    payload.executionId());
            return false;
        }
        long priorSequence = locked.getControlStateSequence() == null
                ? 0L : locked.getControlStateSequence();
        if (payload.stateSequence() <= priorSequence) {
            // Stale (out-of-order redelivery) — logged no-op, never regresses;
            // the frame IS acknowledged (the idempotent-durable contract).
            log.debug("execution.control.state sequence {} not newer than {} for execution "
                    + "{} — stale report ignored", payload.stateSequence(), priorSequence,
                    locked.getId());
            return true;
        }
        locked.setControlStateSequence(payload.stateSequence());
        // §22.4: acceptedControlRevision tracks the highest ACCEPTED engine
        // revision the host observed — a REJECTED report never advances it.
        if (payload.status() != ExecutionControlStatePayload.Status.REJECTED) {
            long priorRevision = locked.getAcceptedControlRevision() == null
                    ? 0L : locked.getAcceptedControlRevision();
            if (payload.controlRevision() > priorRevision) {
                locked.setAcceptedControlRevision(payload.controlRevision());
            }
        }
        locked.setHoldState(payload.effectiveState().name());
        locked.setHoldChangedAt(payload.changedAt());
        locked.setIdleResumeAt(payload.idleResumeAt());
        executionRepository.save(locked);
        log.debug("Observed control state {} (seq {}) projected for execution {}",
                payload.status(), payload.stateSequence(), locked.getId());
        return true;
    }

    /** The shared durable-outcome arm (§22.6 complete/failed). */
    private void settleOutcomeArm(WebSocketSession session, HostProtocolEnvelope envelope,
                                  Class<?> payloadType, String frameType,
                                  OutcomeApplier applier) {
        UUID instanceId = (UUID) session.getAttributes().get(ATTR_HOST_INSTANCE_ID);
        if (instanceId == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    frameType + " before host.opened", false, "EXECUTION", null);
            return;
        }
        if (envelope.getExecutionId() == null) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "executionId is required", false, "EXECUTION", null);
            return;
        }
        if (!advertisesSessionInteraction(instanceId)) {
            sendError(session, envelope.getMessageId(), HostProtocol.UNSUPPORTED_MESSAGE,
                    "the connection did not negotiate the sessionInteraction capability (§22.2)",
                    false, "EXECUTION", null);
            return;
        }
        Object payload;
        try {
            payload = objectMapper.treeToValue(envelope.getPayload(), payloadType);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    frameType + " payload failed validation: " + e.getMessage(),
                    false, "EXECUTION", null);
            return;
        }
        try {
            SessionExecution execution = executionRepository.findById(
                    envelope.getExecutionId()).orElse(null);
            if (execution == null) {
                sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        frameType + " for unknown execution", false, "EXECUTION", null);
                return;
            }
            // §22.3 identity walk (the other execution arms' pattern): the
            // execution must belong to THIS connection's session instance.
            if (!instanceId.equals(executionSessionInstanceId(execution))) {
                sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                        "execution does not belong to this connection's session", false,
                        "EXECUTION", null);
                return;
            }
            // §22.3 identity: the payload's execution/dispatch pair must be
            // the admitted execution's own ids (verified again in the
            // service against the locked row).
            if (!envelope.getExecutionId().equals(payloadExecutionIdOf(payload))
                    || !executionIdsMatchDispatch(execution, payload)) {
                sendError(session, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                        "outcome identity does not match the admitted execution", false,
                        "EXECUTION", null);
                return;
            }
            // The service's OWN transaction (settle + usage accounting).
            applier.apply(execution, payload);
            // §12.3: ack AFTER the service transaction committed.
            SessionExecution refreshed = executionRepository.findById(
                    execution.getId()).orElse(execution);
            acknowledge(session, envelope.getMessageId(), refreshed.getSessionId(), 0L);
        } catch (ai.myrmec.engine.inference.execution.interaction.InteractionProtocolException e) {
            // §22.3: the service's protocol refusals surface as protocol.error
            // with the SAME code (IDENTITY_MISMATCH / INVALID_MESSAGE).
            sendError(session, envelope.getMessageId(), e.getCode(),
                    e.getMessage(), false, "EXECUTION", null);
        } catch (ai.myrmec.engine._system.exception.ResourceNotFoundException e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                    frameType + " for unknown execution", false, "EXECUTION", null);
        } catch (Exception e) {
            sendError(session, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    frameType + " payload invalid: " + e.getMessage(), false, "EXECUTION", null);
        }
    }

    /** The §22.3 capability gate: the connection negotiated sessionInteraction. */
    private boolean advertisesSessionInteraction(UUID instanceId) {
        Object capability = connectionManager.getAdvertisedCapabilities(instanceId)
                .get("sessionInteraction");
        return capability instanceof ai.myrmec.engine.websocket.host.payload
                .SessionInteractionCapability
                || capability instanceof Map<?, ?>;
    }

    /** The payload's payload.executionId (typed per frame family). */
    private java.util.UUID payloadExecutionIdOf(Object payload) {
        return switch (payload) {
            case ExecutionInteractionCompletePayload complete -> complete.executionId();
            case ExecutionInteractionFailedPayload failed -> failed.executionId();
            case ExecutionInteractionDeltaPayload delta -> delta.executionId();
            case ExecutionControlStatePayload state -> state.executionId();
            default -> null;
        };
    }

    /** The payload's dispatchId must equal the execution's own (§22.3). */
    private boolean executionIdsMatchDispatch(SessionExecution execution, Object payload) {
        java.util.UUID dispatchId = switch (payload) {
            case ExecutionInteractionCompletePayload complete -> complete.dispatchId();
            case ExecutionInteractionFailedPayload failed -> failed.dispatchId();
            case ExecutionInteractionDeltaPayload delta -> delta.dispatchId();
            case ExecutionControlStatePayload state -> state.dispatchId();
            default -> null;
        };
        return dispatchId != null && dispatchId.equals(execution.getDispatchId());
    }

    /** The outcome applier seam (complete/failed call their service arms). */
    @FunctionalInterface
    private interface OutcomeApplier {
        void apply(SessionExecution execution, Object payload);
    }

    /** The durable host serving the execution's session (null when unresolvable). */
    private UUID agentIdOfExecution(SessionExecution execution) {
        UUID sessionIdInstance = executionSessionInstanceId(execution);
        if (sessionIdInstance == null) {
            return null;
        }
        return instanceRepository.findById(sessionIdInstance)
                .map(AgentHostInstance::getAgentHostId)
                .orElse(null);
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
            } else {
                // §12.2: a CONVERSATION session stays ACTIVE after its turn's
                // terminal — restart the idle lease from the terminal so the
                // next expiry window measures true idleness (not the previous
                // turn's age). PAUSED and WORKFLOW close above; terminal
                // replays short-circuit earlier via isReplay.
                sessionAllocator.touchIdleLease(execution.getSessionId());
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
        // Protocol 8.5: the unified complete frame carries the orchestration
        // result NESTED in result.structured; failed carries the error block
        // plus the redacted result body in the same result.structured block
        // (16's additive-field rule - evidence parity for every terminal);
        // failed additionally carries the retry continuation. The outcome
        // service consumes the result body itself (the legacy
        // orchestration.result was flat), so the bridge unwraps
        // result.structured on COMPLETE and FAILED before applying.
        Map<String, Object> structuredResult = payloadMap;
        if ((terminalState == SessionExecution.State.COMPLETED
                || terminalState == SessionExecution.State.FAILED)
                && payloadNode != null && payloadNode.path("result").path("structured").isObject()) {
            structuredResult = objectMapper.convertValue(
                    payloadNode.path("result").path("structured"),
                    java.util.LinkedHashMap.class);
            // The frame-level usage block (8.5) stays authoritative for the
            // attempt's usage columns; the result body already carries its own.
            if (!structuredResult.containsKey("usage") && payloadNode.path("usage").isObject()) {
                structuredResult.put("usage", objectMapper.convertValue(
                        payloadNode.path("usage"), java.util.LinkedHashMap.class));
            }
            // 16.6 engine-tuple convention: the stored output keeps a
            // top-level errorCode derived from the failure frame's error
            // block (the observation surfaces read it flat).
            if (terminalState == SessionExecution.State.FAILED
                    && payloadNode.path("error").path("code").isTextual()) {
                structuredResult.put("errorCode",
                        payloadNode.path("error").path("code").asText());
            }
            // FAILED disposition: the bridge's onOrchestrationOutcome reads
            // retryable/retryAfterSeconds from an error block - fold the
            // frame's error block into the unwrapped body so BOTH contracts
            // (evidence storage + retry classification) hold.
            if (terminalState == SessionExecution.State.FAILED
                    && payloadNode.path("error").isObject()) {
                structuredResult.put("error", objectMapper.convertValue(
                        payloadNode.path("error"), java.util.LinkedHashMap.class));
            }
        }
        switch (terminalState) {
            case COMPLETED, FAILED, CANCELLED ->
                    executionBridge.onOrchestrationOutcome(dispatchId, execution.getId(), terminalState, structuredResult);
            case PAUSED -> {
                ExecutionPausedPayload paused = objectMapper.convertValue(payloadNode, ExecutionPausedPayload.class);
                executionBridge.onOrchestrationApprovalRequested(dispatchId, mapApprovalRequested(paused));
                // 8.6 paused frame: the stored task/attempt output keeps the
                // RESULT-BODY usage shape (helperCalls/rejectionCount/
                // totalTokens) so COMPLETE and PAUSED persist uniformly -
                // the frame's orchestrationFunctionCalls maps onto it.
                Map<String, Object> pausedResult = new java.util.LinkedHashMap<>(payloadMap);
                Map<String, Object> bodyUsage = new java.util.LinkedHashMap<>();
                bodyUsage.put("helperCalls", paused.usage() == null || paused.usage().orchestrationFunctionCalls() == null
                        ? 0L : paused.usage().orchestrationFunctionCalls());
                bodyUsage.put("rejectionCount", 0L);
                bodyUsage.put("totalTokens", paused.usage() == null || paused.usage().totalTokens() == null
                        ? 0L : paused.usage().totalTokens());
                pausedResult.put("usage", bodyUsage);
                executionBridge.onOrchestrationOutcome(dispatchId, execution.getId(), terminalState, pausedResult);
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
            // §8.7: approval requests ride the channel exactly like the
            // other host→engine execution frames.
            case HostProtocol.EXECUTION_APPROVAL_REQUESTED ->
                    handleExecutionApprovalRequested(channelSocket, envelope);
            // §22.7 (Task 6): the chat-proposal frame rides the dedicated
            // channel like the rest of the execution traffic.
            case HostProtocol.EXECUTION_CONTROL_REQUEST ->
                    handleExecutionControlRequest(channelSocket, envelope);
            // §22.3 (Task 8): the interaction family rides the dedicated
            // channel too (durable complete/failed are dedicated-only in
            // the SDK; the arms behave identically on either socket).
            case HostProtocol.EXECUTION_INTERACTION_COMPLETE ->
                    handleExecutionInteractionComplete(channelSocket, envelope);
            case HostProtocol.EXECUTION_INTERACTION_FAILED ->
                    handleExecutionInteractionFailed(channelSocket, envelope);
            case HostProtocol.EXECUTION_INTERACTION_DELTA ->
                    handleExecutionInteractionDelta(channelSocket, envelope);
            case HostProtocol.EXECUTION_CONTROL_STATE ->
                    handleExecutionControlState(channelSocket, envelope);
            default -> sendError(channelSocket, envelope.getMessageId(),
                    HostProtocol.INVALID_MESSAGE,
                    "Frame type " + type + " is not permitted on the dedicated channel",
                    false, "CONNECTION", null);
        }
    }
}
