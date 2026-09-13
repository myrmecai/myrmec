// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** session.offer payload (§7.1): one tentative slot reservation. */
public record SessionOfferPayload(
        UUID allocationId,
        UUID sessionId,
        String kind,               // CONVERSATION | ORCHESTRATION_TASK
        Ref ref,
        Requirements requirements,
        Lease lease,
        Routing routing) {

    public record Ref(String type, UUID id) {}
    public record Requirements(java.util.List<String> tools,
                               java.util.List<String> runtimes,
                               java.util.List<String> features) {}
    public record Lease(Instant offerExpiresAt, int idleTimeoutSeconds) {}
    public record Routing(String homeNodeId, String homeNodeAddress) {}
}
