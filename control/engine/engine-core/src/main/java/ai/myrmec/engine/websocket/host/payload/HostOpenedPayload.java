// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import ai.myrmec.engine.websocket.host.HostFrameLogRedactor;

import java.util.UUID;

/**
 * host.opened payload (§6.3): the engine's acceptance of one supervisor run.
 *
 * <p>Additive fields (credential-envelope design §6, protocol §16
 * additive-fields rule): {@code psk} — base64 of the run's freshly minted
 * 32-byte pre-shared key, delivered exactly once per mint (same-nonce replay
 * re-delivers the same key) — and {@code pskKeyId}.</p>
 *
 * <p><b>Redaction:</b> {@code psk} is key material in a frame (§3 threat:
 * frame capture after TLS termination). It is on the frame-logger denylist:
 * {@link #toString()} redacts it, and any code that serializes a host-control
 * frame for logging must pass it through
 * {@code HostFrameLogRedactor.redact(...)}.</p>
 */
public record HostOpenedPayload(
        UUID hostInstanceId,
        int protocolVersion,
        int effectivePoolSize,
        int heartbeatIntervalSeconds,
        int offerTimeoutSeconds,
        int eventReplayWindowSeconds,
        StreamLimits streamLimits,
        String serverNodeId,
        String psk,
        UUID pskKeyId) {

    /** §6.3 negotiated stream limits. */
    public record StreamLimits(
            int maxFrameBytes,
            int maxBufferedDeltaBytesPerSession,
            int maxUnacknowledgedEventBytesPerSession,
            int eventBackpressureTimeoutSeconds) {
    }

    /**
     * Records inherit a component-listing {@code toString} — that would put
     * the run key into every log line that mentions this payload. Redact it.
     */
    @Override
    public String toString() {
        return "HostOpenedPayload[hostInstanceId=" + hostInstanceId
                + ", protocolVersion=" + protocolVersion
                + ", effectivePoolSize=" + effectivePoolSize
                + ", heartbeatIntervalSeconds=" + heartbeatIntervalSeconds
                + ", offerTimeoutSeconds=" + offerTimeoutSeconds
                + ", eventReplayWindowSeconds=" + eventReplayWindowSeconds
                + ", streamLimits=" + streamLimits
                + ", serverNodeId=" + serverNodeId
                + ", psk=" + HostFrameLogRedactor.REDACTED
                + ", pskKeyId=" + pskKeyId
                + "]";
    }
}
