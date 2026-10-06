// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import java.time.Instant;
import java.util.UUID;

/**
 * Plan section 4 POST /control-requests/{id}/decision response (200) and the
 * §22.7 disposition shape the propose path resolves with:
 * {controlRequestId, resolutionRevision, status, expiresAt?, commandMessageId?,
 * controlRevision?, errorCode?}. ACCEPTED requires commandMessageId;
 * CONFIRMATION_REQUIRED requires expiresAt (mirrors the §22.7 payload
 * constraints); duplicates reuse bytes.
 */
public record ProposalReceipt(UUID controlRequestId, long resolutionRevision, String status,
                              Instant expiresAt, String commandMessageId,
                              Long controlRevision, String errorCode) {
}