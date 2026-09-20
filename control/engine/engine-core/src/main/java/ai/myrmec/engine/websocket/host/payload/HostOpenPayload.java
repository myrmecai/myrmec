// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** host.open payload (§6.1). */
public record HostOpenPayload(
        UUID instanceNonce,
        String hostname,
        String runtimeVersion,
        List<Integer> supportedProtocolVersions,
        int poolSize,
        Map<String, Object> capabilities,
        Map<String, Object> reportedCapacity,
        /**
         * Local-owner model (§3.7/§4.1, additive): the id of the user logged
         * into the VS Code plugin whose local workspace opened the instance.
         * REQUIRED (non-null) for LOCAL hosts, MUST be absent (null) for
         * MANAGED hosts — the HOST_JWT carries no user identity (§18 token
         * isolation), so this payload field is the only owner channel.
         */
        UUID ownerUserId) {
}
