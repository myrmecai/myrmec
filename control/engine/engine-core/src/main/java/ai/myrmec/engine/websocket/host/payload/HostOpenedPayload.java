// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/** host.opened payload (§6.3): the engine's acceptance of one supervisor run. */
public record HostOpenedPayload(
        UUID hostInstanceId,
        int protocolVersion,
        int effectivePoolSize,
        int heartbeatIntervalSeconds,
        int offerTimeoutSeconds,
        int eventReplayWindowSeconds,
        StreamLimits streamLimits,
        String serverNodeId) {

    /** §6.3 negotiated stream limits. */
    public record StreamLimits(
            int maxFrameBytes,
            int maxBufferedDeltaBytesPerSession,
            int maxUnacknowledgedEventBytesPerSession,
            int eventBackpressureTimeoutSeconds) {
    }
}
