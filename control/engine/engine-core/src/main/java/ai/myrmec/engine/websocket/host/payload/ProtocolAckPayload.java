// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

/** protocol.ack (§12.3) — acknowledges durable host→engine frames. */
public record ProtocolAckPayload(
        String acknowledgedMessageId,
        long highestContiguousSequence,
        String status) {   // "DURABLY_RECORDED"

    public static final String STATUS_DURABLY_RECORDED = "DURABLY_RECORDED";
}
