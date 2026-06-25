package ai.myrmec.engine.node;

import java.util.UUID;

/**
 * Node-to-node relay envelope (slice 4b). Carries an opaque, already-serialized
 * control frame plus the target worker id. The receiving replica pushes
 * {@code frameJson} verbatim to the worker's local socket without ever
 * deserializing it into typed payloads.
 */
public record AgentRelayRequest(UUID agentInstanceId, String frameJson) {
}
