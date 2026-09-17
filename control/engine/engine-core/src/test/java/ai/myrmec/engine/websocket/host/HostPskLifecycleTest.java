// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Credential-envelope design §6/§14: per-instance PSK lifecycle on the
 * host-control socket. First host.open mints a 32-byte key delivered in
 * host.opened; the row keeps only the EncryptionService-encrypted copy;
 * same-nonce replay re-delivers the SAME key (no re-mint); a new nonce is a
 * new run with a new key; close(reason) nulls psk_encrypted and retains
 * psk_key_id for audit; the payload's toString redacts psk (frame-logger
 * denylist). Frames are driven straight into the handler with stub sessions
 * (house pattern from HostControlOpenTest).
 */
class HostPskLifecycleTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private record Setup(WebSocketSession session, UUID instanceId, String pskBase64, UUID pskKeyId) {}

    private Setup openedHost() throws Exception {
        AgentHostCreationResult created = data.agent().named("psk-host").withMaxAgents(10).create();

        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("psk-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, created.agent().getId());
        lenient().when(session.getAttributes()).thenReturn(attrs);

        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID());
        handler.handleMessage(session, new TextMessage(open));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        JsonNode opened = mapper.readTree(captor.getValue().getPayload());
        return new Setup(
                session,
                UUID.fromString(opened.path("payload").path("hostInstanceId").asText()),
                opened.path("payload").path("psk").asText(null),
                opened.path("payload").hasNonNull("pskKeyId")
                        ? UUID.fromString(opened.path("payload").path("pskKeyId").asText())
                        : null);
    }

    private String openFrame(UUID nonce) {
        return """
                { "protocolVersion": 1, "messageId": "m-%s", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": {}, "reportedCapacity": {} } }
                """.formatted(Instant.now(), Instant.now(), nonce);
    }

    private AgentHostInstance row(UUID instanceId) {
        return instances.findById(instanceId).orElseThrow();
    }

    @Test
    void firstOpenMintsAKeyAndDeliversItOnce() throws Exception {
        Setup setup = openedHost();

        byte[] psk = Base64.getDecoder().decode(setup.pskBase64());
        assertThat(psk).hasSize(32);
        assertThat(setup.pskKeyId()).isNotNull();

        AgentHostInstance instance = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(instance.getPskKeyId()).isEqualTo(setup.pskKeyId());
        // At-rest storage (design §14): encrypted copy present and NOT the
        // plaintext; it round-trips through the EncryptionService.
        assertThat(instance.getPskEncrypted()).isNotNull().isNotEqualTo(psk);
        assertThat(encryptionService.decrypt(instance.getPskEncrypted()))
                .isEqualTo(setup.pskBase64());
    }

    @Test
    void sameNonceReplayRedeliversTheSameKey() throws Exception {
        Setup setup = openedHost();
        UUID nonce = instances.findById(setup.instanceId()).orElseThrow().getInstanceNonce() != null
                ? UUID.fromString(instances.findById(setup.instanceId()).orElseThrow().getInstanceNonce())
                : UUID.randomUUID();

        WebSocketSession replaySocket = mock(WebSocketSession.class);
        lenient().when(replaySocket.getId()).thenReturn("psk-sock-2-" + UUID.randomUUID());
        lenient().when(replaySocket.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, hostIdOf(setup.instanceId()));
        lenient().when(replaySocket.getAttributes()).thenReturn(attrs);

        handler.handleMessage(replaySocket, new TextMessage(openFrame(
                UUID.fromString(instances.findById(setup.instanceId()).orElseThrow().getInstanceNonce()))));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(replaySocket).sendMessage(captor.capture());
        JsonNode replayed = mapper.readTree(captor.getValue().getPayload()).path("payload");

        // Byte-identical key, same keyId, still one OPEN instance.
        assertThat(replayed.path("psk").asText()).isEqualTo(setup.pskBase64());
        assertThat(UUID.fromString(replayed.path("pskKeyId").asText())).isEqualTo(setup.pskKeyId());
        assertThat(instances.findByAgentHostIdAndStatus(hostIdOf(setup.instanceId()),
                AgentHostInstance.Status.OPEN)).hasSize(1);
    }

    @Test
    void newNonceIsANewRunWithANewKey() throws Exception {
        Setup setup = openedHost();
        UUID hostId = hostIdOf(setup.instanceId());

        WebSocketSession second = mock(WebSocketSession.class);
        lenient().when(second.getId()).thenReturn("psk-sock-3-" + UUID.randomUUID());
        lenient().when(second.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, hostId);
        lenient().when(second.getAttributes()).thenReturn(attrs);

        handler.handleMessage(second, new TextMessage(openFrame(UUID.randomUUID())));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(second).sendMessage(captor.capture());
        JsonNode secondOpen = mapper.readTree(captor.getValue().getPayload()).path("payload");

        UUID newInstance = UUID.fromString(secondOpen.path("hostInstanceId").asText());
        assertThat(newInstance).isNotEqualTo(setup.instanceId());
        assertThat(UUID.fromString(secondOpen.path("pskKeyId").asText()))
                .isNotEqualTo(setup.pskKeyId());
        assertThat(secondOpen.path("psk").asText()).isNotEqualTo(setup.pskBase64());

        // The superseded run's row: key erased (it went through
        // close("SUPERSEDED") — same erasure rule as any close) while the
        // keyId is retained for audit.
        AgentHostInstance oldRow = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(oldRow.getStatus()).isEqualTo(AgentHostInstance.Status.CLOSED);
        assertThat(oldRow.getCloseReason()).isEqualTo("SUPERSEDED");
        assertThat(oldRow.getPskKeyId()).isEqualTo(setup.pskKeyId());
        assertThat(oldRow.getPskEncrypted()).isNull();
    }

    /**
     * §13 (A2): the drop parks the instance RECOVERING — the PSK survives
     * the window (the resume handshake re-delivers the SAME key). Erasure
     * happens only at the terminal close (retention expiry → HOST_LOST).
     */
    @Test
    void dropRetainsTheKeyForTheRecoveryWindowAndExpiryErasesIt() throws Exception {
        Setup setup = openedHost();

        handler.afterConnectionClosed(setup.session(), CloseStatus.NORMAL);

        AgentHostInstance row = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(AgentHostInstance.Status.RECOVERING);
        assertThat(row.getPskEncrypted()).isNotNull();
        assertThat(row.getPskKeyId()).isEqualTo(setup.pskKeyId());

        // Retention expiry applies the §9 fallback: terminal close erases.
        instances.saveAndFlush(instances.findById(setup.instanceId()).orElseThrow());
        ai.myrmec.engine.inference.SessionAllocator allocator =
                ai.myrmec.engine.inference.SessionAllocator.class.cast(
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                handler, "sessionAllocator"));
        allocator.expireRecoveredInstances(
                row.getRecoveryExpiresAt().plusSeconds(1));
        AgentHostInstance expired = instances.findById(setup.instanceId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(AgentHostInstance.Status.CLOSED);
        assertThat(expired.getPskEncrypted()).isNull();
        assertThat(expired.getPskKeyId()).isEqualTo(setup.pskKeyId());
    }

    @Test
    void payloadToStringRedactsPsk() throws Exception {
        Setup setup = openedHost();

        assertThat(setup.pskBase64()).isNotBlank();
        assertThat(new ai.myrmec.engine.websocket.host.payload.HostOpenedPayload(
                setup.instanceId(), 1, 1, 15, 10, 3600,
                new ai.myrmec.engine.websocket.host.payload.HostOpenedPayload.StreamLimits(
                        65536, 262144, 8388608, 30),
                "node-1",
                setup.pskBase64(),
                setup.pskKeyId()).toString())
                .doesNotContain(setup.pskBase64())
                .contains("<redacted>")
                .contains(setup.pskKeyId().toString());
    }

    @Test
    void frameLogRedactorStripsPskFromSerializedFrames() throws Exception {
        Setup setup = openedHost();

        String json = mapper.writeValueAsString(mapper.readTree(("""
                { "type": "host.opened", "payload": { "psk": "%s", "pskKeyId": "%s" } }
                """).formatted(setup.pskBase64(), setup.pskKeyId())));

        String redacted = HostFrameLogRedactor.redact(json);
        assertThat(redacted)
                .doesNotContain(setup.pskBase64())
                .contains("\"psk\":\"<redacted>\"");

        // Record toString shape: psk=<value> is redacted too.
        assertThat(HostFrameLogRedactor.redact("psk=" + setup.pskBase64() + ", pskKeyId=x"))
                .doesNotContain(setup.pskBase64())
                .contains("psk=<redacted>");
    }

    private UUID hostIdOf(UUID instanceId) {
        return instances.findById(instanceId).orElseThrow().getAgentHostId();
    }
}