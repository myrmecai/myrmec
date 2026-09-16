// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.message.payload;

import ai.myrmec.engine.inference.security.SecureEnvelope;

/**
 * One delivered session credential on {@code session.open} (design §9):
 * the {@code credentialRef} that tool/model/workspace config references,
 * its delivery purpose, and the sealed envelope. Secrets appear only
 * inside {@link #envelope()}.
 */
public record SessionCredential(
        String credentialRef,
        String purpose,
        SecureEnvelope envelope) {}