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
        Map<String, Object> reportedCapacity) {
}
