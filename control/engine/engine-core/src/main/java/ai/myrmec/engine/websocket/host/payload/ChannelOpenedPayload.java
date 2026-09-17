// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/** channel.opened (§7.5): the dedicated channel is bound. */
public record ChannelOpenedPayload(
        UUID sessionId,
        long highestContiguousSequence) {}