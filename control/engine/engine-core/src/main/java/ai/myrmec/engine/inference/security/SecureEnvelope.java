// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.security;

/**
 * {@code MyrmecSecureEnvelopeV1} — a sealed AES-256-GCM session credential
 * (credential-envelope design §8). Secrets appear ONLY inside the
 * {@code ciphertext} field; every other field is non-secret metadata the
 * host needs to re-compute the AAD. The ciphertext field carries the GCM
 * tag appended (last 16 bytes), the layout both Java {@code AES/GCM/NoPadding}
 * and node {@code createDecipheriv('aes-256-gcm')} use — byte-compatible
 * with the SDK's open path in {@code agents/src/security/credentialEnvelopes.ts}.
 */
public record SecureEnvelope(
        String format,
        String keyId,
        String sessionId,
        String hostId,
        String purpose,
        String createdAt,
        String expiresAt,
        String plaintextDigest,
        String nonce,
        String ciphertext) {}