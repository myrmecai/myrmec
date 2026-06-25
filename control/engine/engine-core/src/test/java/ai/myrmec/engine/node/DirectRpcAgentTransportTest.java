package ai.myrmec.engine.node;

import ai.myrmec.engine.websocket.AgentConnectionManager;
import ai.myrmec.engine.websocket.message.MessageType;
import ai.myrmec.engine.websocket.message.WebSocketMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DirectRpcAgentTransportTest {

    private static final String SELF = "node-self";
    private static final String PEER = "node-peer";

    private AgentConnectionManager connectionManager;
    private EngineNodeRepository nodeRepository;
    private NodeRegistryService nodeRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final UUID workerId = UUID.randomUUID();
    private final WebSocketMessage<String> frame =
            WebSocketMessage.of(MessageType.AGENT_BIND, "payload");

    @BeforeEach
    void setUp() {
        connectionManager = mock(AgentConnectionManager.class);
        nodeRepository = mock(EngineNodeRepository.class);
        nodeRegistry = mock(NodeRegistryService.class);
        when(nodeRegistry.getSelfNodeId()).thenReturn(SELF);
    }

    private DirectRpcAgentTransport transport(boolean relayEnabled, String secret) {
        return new DirectRpcAgentTransport(
                connectionManager, nodeRepository, nodeRegistry, objectMapper, relayEnabled, secret);
    }

    private DirectRpcAgentTransport recordingTransport(boolean relayEnabled, String secret,
                                                       AtomicReference<String> capturedAddress,
                                                       boolean postResult) {
        return new DirectRpcAgentTransport(
                connectionManager, nodeRepository, nodeRegistry, objectMapper, relayEnabled, secret) {
            @Override
            protected boolean postToPeer(String peerAddress, AgentRelayRequest request) {
                capturedAddress.set(peerAddress);
                return postResult;
            }
        };
    }

    @Test
    void nullHomeNode_deliversLocally() {
        when(connectionManager.sendMessage(eq(workerId), any())).thenReturn(true);

        boolean result = transport(false, "").sendToNode(null, workerId, frame);

        assertThat(result).isTrue();
        verify(connectionManager).sendMessage(workerId, frame);
        verify(nodeRepository, never()).findById(any());
    }

    @Test
    void selfHomeNode_deliversLocally() {
        when(connectionManager.sendMessage(eq(workerId), any())).thenReturn(true);

        boolean result = transport(true, "secret").sendToNode(SELF, workerId, frame);

        assertThat(result).isTrue();
        verify(connectionManager).sendMessage(workerId, frame);
        verify(nodeRepository, never()).findById(any());
    }

    @Test
    void peerHomeNode_relayDisabled_returnsFalse() {
        boolean result = transport(false, "secret").sendToNode(PEER, workerId, frame);

        assertThat(result).isFalse();
        verify(connectionManager, never()).sendMessage(any(UUID.class), any());
        verify(nodeRepository, never()).findById(any());
    }

    @Test
    void peerHomeNode_blankSecret_returnsFalse() {
        boolean result = transport(true, "").sendToNode(PEER, workerId, frame);

        assertThat(result).isFalse();
        verify(nodeRepository, never()).findById(any());
    }

    @Test
    void peerHomeNode_unknownNode_returnsFalse() {
        when(nodeRepository.findById(PEER)).thenReturn(Optional.empty());

        boolean result = transport(true, "secret").sendToNode(PEER, workerId, frame);

        assertThat(result).isFalse();
    }

    @Test
    void peerHomeNode_downNode_returnsFalse() {
        EngineNode down = new EngineNode();
        down.setNodeId(PEER);
        down.setAddress("10.0.0.9:9090");
        down.setStatus(EngineNode.Status.DOWN);
        when(nodeRepository.findById(PEER)).thenReturn(Optional.of(down));

        boolean result = transport(true, "secret").sendToNode(PEER, workerId, frame);

        assertThat(result).isFalse();
    }

    @Test
    void peerHomeNode_upNode_postsToPeerAddress() {
        EngineNode up = new EngineNode();
        up.setNodeId(PEER);
        up.setAddress("10.0.0.9:9090");
        up.setStatus(EngineNode.Status.UP);
        when(nodeRepository.findById(PEER)).thenReturn(Optional.of(up));

        AtomicReference<String> captured = new AtomicReference<>();
        boolean result = recordingTransport(true, "secret", captured, true)
                .sendToNode(PEER, workerId, frame);

        assertThat(result).isTrue();
        assertThat(captured.get()).isEqualTo("10.0.0.9:9090");
        verify(connectionManager, never()).sendMessage(any(UUID.class), any());
    }
}
