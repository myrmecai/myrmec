package ai.myrmec.engine.node;

/**
 * Resolution result for {@code GET /api/v1/agent/nodes/{nodeId}} (slice 4b).
 * Lets an Agent Host look up the in-cluster address of the replica that owns a
 * worker's conversation socket so it can dial that pod directly.
 */
public record NodeAddressResponse(String nodeId, String address, String status) {
}
