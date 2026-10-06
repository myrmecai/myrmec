// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.util.UUID;

/**
 * Plan section 4 POST /controls + POST /cancel response (202):
 * {controlRequestId, controlRevision, status}. {@code status} is the intent
 * disposition AT COMMIT — command dispatch committed, NOT action completion
 * (§22.7). Identical retries replay the SAME record.
 */
public record ControlReceipt(UUID controlRequestId, Long controlRevision, String status) {
}