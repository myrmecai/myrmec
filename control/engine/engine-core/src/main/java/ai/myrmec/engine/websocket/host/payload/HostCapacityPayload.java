// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.Map;

/** host.capacity payload (§6.5). A shrink never evicts active sessions. */
public record HostCapacityPayload(
        int poolSize,
        String reason,
        Map<String, Object> reportedCapacity) {
}
