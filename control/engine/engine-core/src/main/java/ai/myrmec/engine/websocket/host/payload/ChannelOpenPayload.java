// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/** channel.open (§7.5): bind a dedicated transport to a session. */
public record ChannelOpenPayload(
        UUID sessionId,
        Long resumeFromSequence) {}