// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.host.HostControlHandshakeInterceptor;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Shared harness for the unified workflow dispatch tests: a real host on the
 * host-control socket (a genuine {@code host.open} mints the live
 * {@code AgentHostInstance}), plus the accept/opened drivers that resume a
 * parked dispatch (&sect;7.3/&sect;7.4).
 *
 * <p>The dispatcher no longer talks to an agent instance directly: it offers a
 * session to the host and waits for the host to answer. Every test that drives
 * {@code TaskDispatcherService} therefore has to play the host's two answers, so
 * the same few lines would otherwise be repeated in each of them.</p>
 */
abstract class WorkflowDispatchSupport extends IntegrationTestBase {

    @Autowired protected TestDataBuilder data;
    @Autowired protected HostControlWebSocketHandler hostHandler;

    protected static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    /** A host that is live on the control socket, with its outbound frames captured. */
    protected record SocketHost(AgentHost host, AgentProfile profile, WebSocketSession session,
                                UUID instanceId, BlockingQueue<String> outbound) {}

    /**
     * A real host with a live OPEN instance: {@code host.open} on a mock session
     * creates the {@code agent_host_instances} row the allocator reserves
     * against, so capacity is real rather than registered.
     */
    protected SocketHost openHost(String name, AgentProfile profile, Project project,
                                  int poolSize) throws Exception {
        AgentHostCreationResult created = (project == null
                ? data.agent().named(name)
                : data.agent().named(name).inProject(project))
                .withProfile(profile).withMaxAgents(Math.max(poolSize, 4)).create();
        AgentHost host = created.agent();

        BlockingQueue<String> outbound = new LinkedBlockingQueue<>();
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("wf-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_NAME, host.getName());
        lenient().when(session.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            outbound.add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": %d,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID(), poolSize);
        ((WebSocketHandler) hostHandler).handleMessage(session, new TextMessage(open));

        JsonNode opened = awaitFrame(outbound, "host.opened");
        UUID instanceId = UUID.fromString(
                opened.path("payload").path("hostInstanceId").asText());
        return new SocketHost(host, profile, session, instanceId, outbound);
    }

    /** Drive one inbound host frame through the real handler. */
    protected void sendHost(SocketHost host, String frame) throws Exception {
        ((WebSocketHandler) hostHandler).handleMessage(host.session(), new TextMessage(frame));
    }

    /**
     * A NEW supervisor run for the same host — a fresh {@code OPEN} instance row
     * plus the live control socket that serves it, with its frames appended to
     * the host's queue. This is what a respawn/reconnect looks like to the
     * engine: the host identity is unchanged, the instance (and therefore the
     * socket the engine addresses) is new.
     *
     * @return the new instance id
     */
    protected UUID reopenHost(SocketHost host, int poolSize) throws Exception {
        ai.myrmec.engine.agent.AgentHostInstance instance =
                agentHostInstanceRepository.saveAndFlush(
                        ai.myrmec.engine.agent.AgentHostInstance.open(
                                host.host(), null, UUID.randomUUID().toString(),
                                "laptop", poolSize, Map.of("cpuCount", 8), "engine-node-1"));

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("wf-reopen-" + instance.getId());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.host().getId());
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_NAME, host.host().getName());
        attrs.put(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID, instance.getId());
        lenient().when(session.getAttributes()).thenReturn(attrs);
        doAnswer(inv -> {
            host.outbound().add(((TextMessage) inv.getArgument(0)).getPayload());
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        hostHandler.getConnectionManager().register(instance.getId(), session);
        return instance.getId();
    }

    /** Close every live instance of a host (what an abnormal disconnect does). */
    protected void closeLiveInstances(UUID hostId) {
        for (ai.myrmec.engine.agent.AgentHostInstance instance : agentHostInstanceRepository
                .findByAgentHostIdAndStatus(hostId,
                        ai.myrmec.engine.agent.AgentHostInstance.Status.OPEN)) {
            instance.close("ABNORMAL_DISCONNECT");
            agentHostInstanceRepository.save(instance);
        }
    }

    /** All engine→host frames of one type, in arrival order. */
    protected java.util.List<JsonNode> framesOfType(SocketHost host, String type) {
        return framesOf(host).stream()
                .filter(f -> type.equals(f.path("type").asText()))
                .toList();
    }

    /** §7.2: the host committed a local slot for the offered session. */
    protected void acceptSession(SocketHost host, UUID sessionId) throws Exception {
        sendHost(host, """
                { "protocolVersion": 1, "messageId": "m-acc-%s", "type": "session.accept",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "allocationId": "%s", "sessionId": "%s", "acceptedAt": "%s" } }
                """.formatted(UUID.randomUUID(), Instant.now(), host.instanceId(), sessionId,
                sessionId, sessionId, Instant.now()));
    }

    /** §7.4: context installed — the session becomes ACTIVE. */
    protected void openSession(SocketHost host, UUID sessionId) throws Exception {
        sendHost(host, """
                { "protocolVersion": 1, "messageId": "m-opd-%s", "type": "session.opened",
                  "sentAt": "%s", "hostInstanceId": "%s", "sessionId": "%s",
                  "payload": { "sessionId": "%s", "ready": true, "channelMode": "CONTROL" } }
                """.formatted(UUID.randomUUID(), Instant.now(), host.instanceId(), sessionId,
                sessionId));
    }

    /** Drive the full §7.2→§7.4 handshake for the session the host was offered. */
    protected UUID completeHandshake(SocketHost host) throws Exception {
        UUID sessionId = offeredSessionId(host);
        acceptSession(host, sessionId);
        openSession(host, sessionId);
        return sessionId;
    }

    /** The session id on the first {@code session.offer} the engine sent. */
    protected UUID offeredSessionId(SocketHost host) {
        JsonNode offer = awaitFrame(host.outbound(), "session.offer");
        return UUID.fromString(offer.path("payload").path("sessionId").asText());
    }

    /** The first engine→host frame of {@code type} seen on the socket. */
    protected JsonNode awaitFrame(BlockingQueue<String> outbound, String type) {
        for (String frame : outbound) {
            JsonNode json = read(frame);
            if (type.equals(json.path("type").asText())) {
                return json;
            }
        }
        throw new AssertionError("no '" + type + "' frame on the host socket; saw: " + outbound);
    }

    /** Every frame the engine sent this host, parsed. */
    protected java.util.List<JsonNode> framesOf(SocketHost host) {
        return host.outbound().stream().map(WorkflowDispatchSupport::read).toList();
    }

    protected static JsonNode read(String frame) {
        try {
            return MAPPER.readTree(frame);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable host frame: " + frame, e);
        }
    }
}
