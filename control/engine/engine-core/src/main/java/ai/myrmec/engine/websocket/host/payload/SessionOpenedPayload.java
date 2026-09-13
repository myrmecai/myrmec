// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/** session.opened payload (§7.4): context installed, ready for execution. */
public record SessionOpenedPayload(
        UUID sessionId,
        boolean ready,
        String channelMode) {}
