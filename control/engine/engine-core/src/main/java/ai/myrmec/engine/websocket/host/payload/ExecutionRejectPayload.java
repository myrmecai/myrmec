// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

import java.util.UUID;

/** execution.reject (§8.2). */
public record ExecutionRejectPayload(
        UUID executionId, String reasonCode, String message, boolean retryable) {}
