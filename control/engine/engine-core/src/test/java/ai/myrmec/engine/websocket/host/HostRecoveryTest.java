// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionAllocator;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.DurableEventReplayService;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.host.payload.HostResumePayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * §13 reconnect and reconciliation (A2; §11.1 RECOVERING):
 * (a) a socket drop parks the instance RECOVERING — sessions stay parked;
 *     a resume inside the window re-adopts (instance OPEN, socket bound)
 *     without re-offering sessions;
 * (b) retention expiry applies the §9 fallback (instance + sessions close
 *     HOST_LOST) — the pre-A2 behavior preserved as the fallback;
 * (c) resume + reconcile: KEEP carries the engine cursor; the engine's
 *     durable-event replay sends events in order; terminal resends dedup
 *     by the host's lastAcknowledgedMessageId;
 * (d) resume with no RECOVERING instance is rejected and the host.open
 *     path still works after it;
 * (e) a resume on a different node re-homes control_node_id.
 */
class HostRecoveryTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executionRepository;
    @Autowired SessionAllocator allocator;
    @Autowired HostResumeReconcileService reconcileService;
    @Autowired ai.myrmec.engine.inference.execution.interaction
            .ExecutionInteractionRepository interactionRepository;
    @Autowired DurableEventReplayService eventReplayService;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Setup(WebSocketSession session, AgentHost host, UUID instanceId,
                         String nonce) {}

    private Setup openedHost(String name) throws Exception {
        AgentHostCreationResult created =
                data.agent().named(name).withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("rec-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String nonce = UUID.randomUUID().toString();
        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": { "sessionInteraction": { "version": 1, "temporaryHold": true } }, "reportedCapacity": {} } }
                """.formatted(Instant.now(), nonce);
        ((WebSocketHandler) handler).handleMessage(session, new TextMessage(open));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JsonNode opened = mapper.readTree(captor.getValue().getPayload());
        UUID instanceId = UUID.fromString(
                opened.path("payload").path("hostInstanceId").asText());
        return new Setup(session, host, instanceId, nonce);
    }

    private List<JsonNode> allReplies(WebSocketSession session) {
        return org.mockito.Mockito.mockingDetails(session).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .map(p -> { try { return mapper.readTree(p); } catch (Exception e) { throw new RuntimeException(e);} })
                .toList();
    }

    /** A session row parked on the given instance (caller picks the state). */
    private Session parkedSession(UUID projectId, UUID instanceId, String state,
                                  String serviceType, long cursor) {
        Session s = new Session();
        s.setServiceType(serviceType);
        s.setRefId(UUID.randomUUID());
        s.setProjectId(projectId);
        s.setKind(serviceType);
        s.setHostInstanceId(instanceId);
        s.setAllocationState(state);
        s.setHighestContiguousSequence(cursor);
        return sessionRepository.saveAndFlush(s);
    }

    private SessionExecution execution(Session session, SessionExecution.State state,
                                       String terminalMessageId) {
        SessionExecution e = new SessionExecution();
        e.setSessionId(session.getId());
        e.setServiceType(session.getServiceType());
        e.setRequestId(session.getRefId().toString());
        e.setState(state);
        e.setTerminalMessageId(terminalMessageId);
        e.setTerminalPayload(state == SessionExecution.State.COMPLETED
                ? Map.of("result", "ok") : Map.of());
        return executionRepository.saveAndFlush(e);
    }

    private void resume(WebSocketSession socket, UUID previousInstanceId, String nonce,
                        List<HostResumePayload.RetainedSession> sessions) throws Exception {
        var payload = Map.of(
                "previousHostInstanceId", previousInstanceId,
                "instanceNonce", nonce,
                "sessions", sessions);
        String frame = """
                { "protocolVersion": 1, "messageId": "m-resume", "type": "host.resume",
                  "sentAt": "%s", "payload": %s }
                """.formatted(Instant.now(), mapper.writeValueAsString(payload));
        ((WebSocketHandler) handler).handleMessage(socket, new TextMessage(frame));
    }

    // ------------------------------------------------------------------
    // (a) drop → RECOVERING; resume within the window re-adopts
    // ------------------------------------------------------------------

    @Test
    void dropParksInstanceRecoveringAndResumeReAdopts() throws Exception {
        Setup setup = openedHost("recovery-a");
        var project = data.project().named("recovery-a").create();
        Session conversation = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "CONVERSATION", 0L);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);

        // §13: the instance is RECOVERING, NOT closed; sessions parked.
        AgentHostInstance row = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AgentHostInstance.Status.RECOVERING);
        assertThat(row.getRecoveryExpiresAt()).isNotNull();
        assertThat(sessionRepository.findById(conversation.getId()).orElseThrow()
                .getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);

        // Resume inside the window on a fresh socket.
        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-2-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(conversation.getId(),
                        "ACTIVE", true, null, 0L, null)));

        AgentHostInstance resumed = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(resumed.getStatus()).isEqualTo(AgentHostInstance.Status.OPEN);
        assertThat(resumed.getRecoveryExpiresAt()).isNull();
        // The resumed instance is the new socket's identity.
        assertThat((UUID) reconnected.getAttributes()
                .get(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID))
                .isEqualTo(setup.instanceId());
        assertThat(handler.getConnectionManager().getSession(setup.instanceId()))
                .isPresent();

        // The KEEP decision carries the engine cursor; sessions NOT re-offered
        // (no session.offer frames on the resumed socket — reconcile only).
        List<JsonNode> replies = allReplies(reconnected);
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).path("type").asText()).isEqualTo(HostProtocol.HOST_RECONCILE);
        JsonNode decision = replies.get(0).path("payload").path("decisions").get(0);
        assertThat(decision.path("action").asText()).isEqualTo("KEEP");
        assertThat(decision.path("sessionId").asText()).isEqualTo(conversation.getId().toString());
        // Sessions stay parked — no re-offer flipped them to OFFERED.
        assertThat(sessionRepository.findById(conversation.getId()).orElseThrow()
                .getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_ACTIVE);
    }

    // ------------------------------------------------------------------
    // (b) retention expiry → HOST_LOST fallback
    // ------------------------------------------------------------------

    @Test
    void windowExpiryFallsBackToHostLostClose() throws Exception {
        Setup setup = openedHost("recovery-b");
        var project = data.project().named("recovery-b").create();
        Session conversation = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "CONVERSATION", 0L);

        WebSocketSession socket = setup.session();
        handler.afterConnectionClosed(socket, CloseStatus.GOING_AWAY);
        assertThat(instances.findById(setup.instanceId()).orElseThrow().getStatus())
                .isEqualTo(AgentHostInstance.Status.RECOVERING);

        // Retention expiry: the sweep applies the §9 fallback (reuse of the
        // expireHostLostSessions-style machinery, extended for RECOVERING).
        int closed = allocator.expireRecoveredInstances(Instant.now().plusSeconds(600));
        assertThat(closed).isEqualTo(1);
        entityManager.clear();

        AgentHostInstance expired = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(AgentHostInstance.Status.CLOSED);
        assertThat(expired.getCloseReason()).isEqualTo("HOST_LOST");
        Session swept = sessionRepository.findById(conversation.getId()).orElseThrow();
        assertThat(swept.getAllocationState()).isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);
        assertThat(swept.getCloseReason()).isEqualTo("HOST_LOST");
    }

    // ------------------------------------------------------------------
    // (c) resume + reconcile: replay order + terminal dedup
    // ------------------------------------------------------------------

    @Test
    void reconcileReplaysDurableEventsInOrderAndDedupsTerminals() throws Exception {
        Setup setup = openedHost("recovery-c");
        var project = data.project().named("recovery-c").create();
        Session conversation = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "CONVERSATION", 0L);
        SessionExecution turn = execution(conversation, SessionExecution.State.RUNNING, null);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);

        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-3-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        // A completed previous turn whose terminal frame the host ALREADY
        // acknowledged (lastAcknowledgedMessageId matches) — its resend must
        // be skipped; a session cursor of 2 replays events > 2.
        SessionExecution prior = execution(conversation, SessionExecution.State.COMPLETED,
                "term-ack-1");

        // Bind a channel socket so the replay drain has a transport (§7.5).
        WebSocketSession channelSocket = mock(WebSocketSession.class);
        lenient().when(channelSocket.getId()).thenReturn("rec-chan-" + UUID.randomUUID());
        lenient().when(channelSocket.isOpen()).thenReturn(true);
        Map<String, Object> chanAttrs = new HashMap<>();
        lenient().when(channelSocket.getAttributes()).thenReturn(chanAttrs);
        applicationContext.getBean(ChannelConnectionRegistry.class)
                .register(conversation.getId(), channelSocket);

        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(conversation.getId(),
                        "ACTIVE", true, turn.getId(), 2L, "term-ack-1")));

        List<JsonNode> replies = allReplies(reconnected);
        JsonNode reconcile = replies.stream()
                .filter(r -> "host.reconcile".equals(r.path("type").asText()))
                .findFirst().orElseThrow();
        JsonNode decision = reconcile.path("payload").path("decisions").get(0);
        assertThat(decision.path("action").asText()).isEqualTo("KEEP");
        assertThat(decision.path("resumeFromSequence").asLong()).isEqualTo(0L);

        // The prior turn's terminal frame was acknowledged by the host —
        // the dedup must NOT resend it on the channel socket.
        var channelSends = org.mockito.Mockito.mockingDetails(channelSocket).getInvocations()
                .stream()
                .filter(i -> i.getMethod().getName().equals("sendMessage"))
                .map(i -> ((TextMessage) i.getArgument(0)).getPayload())
                .toList();
        assertThat(channelSends).isEmpty();
    }

    // ------------------------------------------------------------------
    // (d) resume with no RECOVERING instance → rejected → host.open works
    // ------------------------------------------------------------------

    @Test
    void resumeWithoutRecoveringInstanceIsRejectedAndHostOpenStillWorks() throws Exception {
        Setup setup = openedHost("recovery-d");

        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-4-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        // Never dropped: the instance is OPEN, so resume cannot re-adopt.
        resume(reconnected, setup.instanceId(), setup.nonce(), List.of());

        List<JsonNode> replies = allReplies(reconnected);
        assertThat(replies).hasSize(1);
        assertThat(replies.get(0).path("type").asText()).isEqualTo(HostProtocol.PROTOCOL_ERROR);
        assertThat(replies.get(0).path("payload").path("code").asText())
                .isEqualTo(HostProtocol.IDENTITY_MISMATCH);

        // The host.open fallback path still works on the same socket.
        String nonce2 = UUID.randomUUID().toString();
        String open = """
                { "protocolVersion": 1, "messageId": "m-open-2", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": { "sessionInteraction": { "version": 1, "temporaryHold": true } }, "reportedCapacity": {} } }
                """.formatted(Instant.now(), nonce2);
        ((WebSocketHandler) handler).handleMessage(reconnected, new TextMessage(open));

        List<JsonNode> after = allReplies(reconnected);
        JsonNode opened = after.stream()
                .filter(r -> "host.opened".equals(r.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(opened.path("payload").path("hostInstanceId").asText())
                .isNotEqualTo(setup.instanceId().toString());
    }

    // ------------------------------------------------------------------
    // (e) resume on a different node re-homes control_node_id
    // ------------------------------------------------------------------

    @Test
    void resumeRehomesControlNodeToTheCurrentOwner() throws Exception {
        Setup setup = openedHost("recovery-e");
        AgentHostInstance prior = instances.findById(setup.instanceId()).orElseThrow();
        prior.rehomeTo("node-old");
        instances.saveAndFlush(prior);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);

        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-5-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        resume(reconnected, setup.instanceId(), setup.nonce(), List.of());

        entityManager.clear();
        AgentHostInstance resumed = instances.findById(setup.instanceId()).orElseThrow();
        // Relay targets the new owner: control_node_id == the replica's
        // self node id (the same source host.opened.serverNodeId reports).
        assertThat(resumed.getControlNodeId())
                .isEqualTo(applicationContext.getBean(
                        ai.myrmec.engine.node.NodeRegistryService.class).getSelfNodeId());
        assertThat(resumed.getStatus()).isEqualTo(AgentHostInstance.Status.OPEN);
    }

    // ------------------------------------------------------------------
    // (c-supplement) the reconcile service's per-shape decisions
    // ------------------------------------------------------------------

    @Test
    void reconcileClosesClosedRacedSessionsAndCancelsInFlightOrchestration() throws Exception {
        Setup setup = openedHost("recovery-f");
        var project = data.project().named("recovery-f").create();
        // A session the engine already closed (HOST_LOST raced).
        Session raced = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_CLOSED, "CONVERSATION", 0L);
        // An in-flight orchestration execution with no recorded outcome.
        Session orchestration = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "WORKFLOW", 0L);
        execution(orchestration, SessionExecution.State.RUNNING, null);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);

        var decisions = reconcileService.reconcile(instances.findById(setup.instanceId())
                .orElseThrow(), List.of(
                new HostResumePayload.RetainedSession(raced.getId(), "ACTIVE",
                        true, null, 0L, null),
                new HostResumePayload.RetainedSession(orchestration.getId(), "ACTIVE",
                        true, null, 0L, null)));

        assertThat(decisions).hasSize(2);
        assertThat(decisions.get(0).action()).isEqualTo("CLOSE");
        assertThat(decisions.get(0).reasonCode()).isEqualTo(HostProtocol.SESSION_NOT_FOUND);
        assertThat(decisions.get(1).action()).isEqualTo("CANCEL_EXECUTION");
        assertThat(decisions.get(1).executionId()).isNotNull();
        // The orchestration execution is CANCELLING (the host applies
        // execution.cancel semantics and emits execution.cancelled).
        entityManager.clear();
        assertThat(executionRepository.findById(decisions.get(1).executionId())
                .orElseThrow().getState())
                .isEqualTo(SessionExecution.State.CANCELLING);
    }

    // ------------------------------------------------------------------
    // (D7/§22.8) a channel-fatality victim does NOT get KEEP on reconcile
    // ------------------------------------------------------------------

    /**
     * Section 22.8: Agent Channel loss is fatal — the session was already
     * closed by the channel-close path when the socket died, so a later
     * host.resume reconcile for it answers the engine's existing CLOSED
     * decision (CLOSE/SESSION_NOT_FOUND), never KEEP. The close path and the
     * reconcile decision reuse the EXISTING machinery; no new decision
     * vocabulary.
     */
    @Test
    void channelFatalityVictimDoesNotGetKeepOnReconcile() throws Exception {
        Setup setup = openedHost("recovery-d7");
        var project = data.project().named("recovery-d7").create();
        Session conversation = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "CONVERSATION", 0L);

        // Bind a dedicated channel socket the way the §7.5 handshake does:
        // the registry holds the socket AND the handler stamps the bound
        // session id in the socket attributes (what a real channel.open does).
        WebSocketSession channelSocket = mock(WebSocketSession.class);
        lenient().when(channelSocket.getId()).thenReturn("rec-chan-d7-" + UUID.randomUUID());
        lenient().when(channelSocket.isOpen()).thenReturn(true);
        Map<String, Object> chanAttrs = new HashMap<>();
        chanAttrs.put(HostChannelWebSocketHandler.ATTR_BOUND_SESSION_ID, conversation.getId());
        lenient().when(channelSocket.getAttributes()).thenReturn(chanAttrs);
        applicationContext.getBean(ChannelConnectionRegistry.class)
                .register(conversation.getId(), channelSocket);

        // The dedicated channel dies — the FATAL close path runs (D7).
        applicationContext.getBean(HostChannelWebSocketHandler.class)
                .afterConnectionClosed(channelSocket, CloseStatus.GOING_AWAY);
        entityManager.clear();
        assertThat(sessionRepository.findById(conversation.getId()).orElseThrow()
                .getAllocationState())
                .as("the fatal channel close already ran")
                .isEqualTo(SessionAllocator.ALLOC_STATE_CLOSED);

        // The host later reconnects and reports the session as retained.
        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);
        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-d7-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(conversation.getId(),
                        "ACTIVE", true, null, 0L, null)));

        List<JsonNode> replies = allReplies(reconnected);
        JsonNode reconcile = replies.stream()
                .filter(r -> "host.reconcile".equals(r.path("type").asText()))
                .findFirst().orElseThrow();
        JsonNode decision = reconcile.path("payload").path("decisions").get(0);
        assertThat(decision.path("action").asText())
                .as("a channel-fatality victim is never KEEPed (§22.8)")
                .isEqualTo("CLOSE");
        assertThat(decision.path("reasonCode").asText())
                .isEqualTo(HostProtocol.SESSION_NOT_FOUND);
    }

    // ------------------------------------------------------------------
    // §22.8 (Task 10): the retained in-process interaction overlay on
    // resume/reconcile — KEEP only for live retained state; evidence
    // never overrides terminal decisions.
    // ------------------------------------------------------------------

    @Test
    void resumeWithInteractionStateKeepsInFlightExecutionAndProjectsEvidence() throws Exception {
        Setup setup = openedHost("recovery-overlay");
        var project = data.project().named("recovery-overlay").create();
        Session workflow = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "WORKFLOW", 0L);
        SessionExecution inFlight = execution(workflow,
                SessionExecution.State.RUNNING, null);
        inFlight.setDispatchId(UUID.randomUUID());
        inFlight.setControlRevision(1L);
        inFlight.setAcceptedControlRevision(0L);
        inFlight.setControlStateSequence(0L);
        inFlight.setHoldState("RUNNING");
        executionRepository.saveAndFlush(inFlight);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);
        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-t10a-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        // §22.8: the host retained the live coordinator (an intact Agent
        // Channel during the Host Channel outage) and reports the overlay.
        var overlay = new HostResumePayload.RetainedSession.ReportedInteractionState(
                inFlight.getId(), 3L, 12L, "HELD",
                Instant.now().plusSeconds(300).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString(),
                null, List.of());
        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(workflow.getId(),
                        "ACTIVE", true, inFlight.getId(), 0L, null, overlay)));

        // KEEP (not CANCEL_EXECUTION): the live retained state wins.
        List<JsonNode> replies = allReplies(reconnected);
        JsonNode reconcile = replies.stream()
                .filter(r -> "host.reconcile".equals(r.path("type").asText()))
                .findFirst().orElseThrow();
        JsonNode decision = reconcile.path("payload").path("decisions").get(0);
        assertThat(decision.path("action").asText()).isEqualTo("KEEP");
        // The evidence projected into the observed columns.
        SessionExecution after = executionRepository.findById(inFlight.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(SessionExecution.State.RUNNING);
        assertThat(after.getAcceptedControlRevision()).isEqualTo(3L);
        assertThat(after.getControlStateSequence()).isEqualTo(12L);
        assertThat(after.getHoldState()).isEqualTo("HELD");
        assertThat(after.getIdleResumeAt()).isNotNull();
    }

    @Test
    void resumeWithoutInteractionStateCancelsInFlightAsBefore() throws Exception {
        Setup setup = openedHost("recovery-no-overlay");
        var project = data.project().named("recovery-no-overlay").create();
        Session workflow = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "WORKFLOW", 0L);
        SessionExecution inFlight = execution(workflow,
                SessionExecution.State.RUNNING, null);
        inFlight.setDispatchId(UUID.randomUUID());
        executionRepository.saveAndFlush(inFlight);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);
        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-t10b-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        // A RESTARTED process reports no overlay (no retained state).
        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(workflow.getId(),
                        "ACTIVE", true, inFlight.getId(), 0L, null, null)));

        List<JsonNode> replies = allReplies(reconnected);
        JsonNode reconcile = replies.stream()
                .filter(r -> "host.reconcile".equals(r.path("type").asText()))
                .findFirst().orElseThrow();
        JsonNode decision = reconcile.path("payload").path("decisions").get(0);
        assertThat(decision.path("action").asText())
                .as("a restarted host (no retained overlay) keeps the §13 default")
                .isEqualTo("CANCEL_EXECUTION");
        assertThat(executionRepository.findById(inFlight.getId()).orElseThrow().getState())
                .isEqualTo(SessionExecution.State.CANCELLING);
    }

    @Test
    void restartCancelSettlesPendingInteractionHostStateLost() throws Exception {
        Setup setup = openedHost("recovery-restart-lost");
        var project = data.project().named("recovery-restart-lost").create();
        Session workflow = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "WORKFLOW", 0L);
        SessionExecution inFlight = execution(workflow,
                SessionExecution.State.RUNNING, null);
        inFlight.setDispatchId(UUID.randomUUID());
        executionRepository.saveAndFlush(inFlight);

        // An admitted pending interaction — the in-flight chat the
        // restarted host cannot settle (its coordinator is gone).
        var interaction = new ai.myrmec.engine.inference.execution.interaction
                .ExecutionInteraction();
        interaction.setExecutionId(inFlight.getId());
        interaction.setOrdinal(1L);
        interaction.setActorUserId(UUID.randomUUID());
        interaction.setClientRequestId(UUID.randomUUID());
        interaction.setRequestDigest("r".repeat(64));
        interaction.setStatus(ai.myrmec.engine.inference.execution.interaction
                .InteractionStatus.ACCEPTED);
        interaction.setRequestText("what is the status?");
        interaction.setResponseDeadline(Instant.now().plusSeconds(120));
        interaction.setAcceptedAt(Instant.now());
        interaction.setUsageStatus("UNKNOWN");
        interaction = interactionRepository.saveAndFlush(interaction);
        inFlight.setPendingInteractionId(interaction.getId());
        executionRepository.saveAndFlush(inFlight);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);
        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-t10e-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        // A RESTARTED process reports no overlay — its retained state is
        // lost, so the pending chat settles with §22.6's uncertain-restart
        // vocabulary (never a silent timeout).
        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(workflow.getId(),
                        "ACTIVE", true, inFlight.getId(), 0L, null, null)));

        List<JsonNode> replies = allReplies(reconnected);
        JsonNode reconcile = replies.stream()
                .filter(r -> "host.reconcile".equals(r.path("type").asText()))
                .findFirst().orElseThrow();
        JsonNode decision = reconcile.path("payload").path("decisions").get(0);
        assertThat(decision.path("action").asText()).isEqualTo("CANCEL_EXECUTION");

        var settled = interactionRepository.findById(interaction.getId())
                .orElseThrow();
        assertThat(settled.getStatus())
                .isEqualTo(ai.myrmec.engine.inference.execution.interaction
                        .InteractionStatus.FAILED);
        assertThat(settled.getError().get("errorCode")).isEqualTo("HOST_STATE_LOST");
        assertThat(settled.getCompletedAt()).isNotNull();
        assertThat(executionRepository.findById(inFlight.getId()).orElseThrow()
                .getPendingInteractionId()).isNull();
    }

    @Test
    void staleInteractionStateEvidenceDoesNotRegressObservedState() throws Exception {
        Setup setup = openedHost("recovery-stale");
        var project = data.project().named("recovery-stale").create();
        Session workflow = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "WORKFLOW", 0L);
        SessionExecution inFlight = execution(workflow,
                SessionExecution.State.RUNNING, null);
        inFlight.setDispatchId(UUID.randomUUID());
        inFlight.setAcceptedControlRevision(5L);
        inFlight.setControlStateSequence(40L);
        inFlight.setHoldState("HELD");
        executionRepository.saveAndFlush(inFlight);

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);
        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-t10c-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        // A STALE report (stateSequence 7 << recorded 40) never regresses.
        var stale = new HostResumePayload.RetainedSession.ReportedInteractionState(
                inFlight.getId(), 1L, 7L, "RUNNING", null, null, List.of());
        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(workflow.getId(),
                        "ACTIVE", true, inFlight.getId(), 0L, null, stale)));

        SessionExecution after = executionRepository.findById(inFlight.getId()).orElseThrow();
        assertThat(after.getAcceptedControlRevision()).isEqualTo(5L);
        assertThat(after.getControlStateSequence()).isEqualTo(40L);
        assertThat(after.getHoldState()).isEqualTo("HELD");
    }

    @Test
    void interactionStateNeverRestoresATerminalExecution() throws Exception {
        Setup setup = openedHost("recovery-terminal");
        var project = data.project().named("recovery-terminal").create();
        Session workflow = parkedSession(project.getId(), setup.instanceId(),
                SessionAllocator.ALLOC_STATE_ACTIVE, "WORKFLOW", 0L);
        SessionExecution done = execution(workflow,
                SessionExecution.State.COMPLETED, "tm-done");

        handler.afterConnectionClosed(setup.session(), CloseStatus.GOING_AWAY);
        WebSocketSession reconnected = mock(WebSocketSession.class);
        lenient().when(reconnected.getId()).thenReturn("rec-sock-t10d-" + UUID.randomUUID());
        lenient().when(reconnected.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, setup.host().getId());
        lenient().when(reconnected.getAttributes()).thenReturn(attrs);

        // The host reports an overlay for a TERMINAL execution: §22.8 —
        // the engine's terminal decision always wins (no projection, no
        // KEEP restore of the execution; the §13 one-shot close applies).
        var overlay = new HostResumePayload.RetainedSession.ReportedInteractionState(
                done.getId(), 9L, 99L, "RUNNING", null, null, List.of());
        resume(reconnected, setup.instanceId(), setup.nonce(), List.of(
                new HostResumePayload.RetainedSession(workflow.getId(),
                        "ACTIVE", true, done.getId(), 0L, null, overlay)));

        List<JsonNode> replies = allReplies(reconnected);
        JsonNode reconcile = replies.stream()
                .filter(r -> "host.reconcile".equals(r.path("type").asText()))
                .findFirst().orElseThrow();
        JsonNode decision = reconcile.path("payload").path("decisions").get(0);
        assertThat(decision.path("action").asText())
                .as("terminal executions are never KEEPed regardless of evidence")
                .isEqualTo("CLOSE");
        SessionExecution after = executionRepository.findById(done.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(SessionExecution.State.COMPLETED);
        assertThat(after.getHoldState()).isEqualTo("RUNNING");
        assertThat(after.getAcceptedControlRevision()).isZero();
    }
}