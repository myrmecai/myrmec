// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** execution.event (§8.4) — durable, at-least-once, acknowledged by §12.3 protocol.ack. */
public record ExecutionEventPayload(
        UUID executionId, UUID eventId, String eventType, Instant occurredAt,
        Map<String, Object> data) {}
