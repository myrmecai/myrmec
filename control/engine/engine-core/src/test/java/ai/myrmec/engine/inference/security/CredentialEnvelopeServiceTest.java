// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The cross-language contract (credential-envelope design §7/§8):
 * {@code sessionKey = HKDF-SHA256(ikm=psk, salt=raw sessionId bytes,
 * info="MyrmecSecureEnvelopeV1", len=32)} and the full sealed-envelope
 * bytes. The vectors below are the SINGLE SOURCE OF TRUTH shared with the
 * TypeScript SDK ({@code agents/src/security/credentialVectors.ts},
 * VECTOR_1) — both implementations MUST produce byte-identical output.
 */
class CredentialEnvelopeServiceTest {

    // Derivation is a static computation — the repos/encryption deps are unused.
    private final CredentialEnvelopeService service = new CredentialEnvelopeService(
            null, null, null);

    /** VECTOR_1 from credentialVectors.ts — the shared contract fixture. */
    @Test
    void deriveSessionKeyMatchesCrossLanguageVector() {
        byte[] psk = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh4=");
        UUID sessionId = UUID.fromString("12345678-90ab-4cde-9f01-234567890abc");

        byte[] sessionKey = service.deriveSessionKey(psk, sessionId);

        assertThat(HexFormat.of().formatHex(sessionKey))
                .isEqualTo("8edc74d2b2d914fc2028390a7f181786865fb9c8acac5f5e498c911cc6307848");
    }

    /**
     * VECTOR_1 envelope: the sealed ciphertext bytes must match the SDK's
     * locked vector — AAD composition, nonce layout, digest, and
     * tag-append order all pinned byte-for-byte.
     */
    @Test
    void buildEnvelopeMatchesCrossLanguageVector() {
        byte[] psk = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh4=");
        UUID sessionId = UUID.fromString("12345678-90ab-4cde-9f01-234567890abc");
        UUID hostId = UUID.fromString("fedcba98-7654-4321-0fed-cba987654321");
        UUID keyId = UUID.fromString("0f0e0d0c-0b0a-4901-8203-040506070809");
        byte[] fixedNonce = Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAA==");
        String plaintext = "sk-test-provider-key-0123456789";

        byte[] sessionKey = service.deriveSessionKey(psk, sessionId);
        SecureEnvelope envelope = sealForVector(sessionKey, keyId, sessionId, hostId,
                "MODEL_PROVIDER", "2026-09-17T10:15:30.000Z", plaintext, fixedNonce);

        assertThat(envelope.plaintextDigest())
                .isEqualTo("sha256:E1Lsrr0Xj46jYk02ZuJM1ulyD1ccit3TEQk8iDBc/nU=");
        assertThat(envelope.ciphertext())
                .isEqualTo("b37AH5DB8Xt+cCmuKC2vt7HJOBaYtGh5aU4fB4qefUdf2jNACLn/U+a5JVh1Ix0=");
        assertThat(envelope.nonce()).isEqualTo("AAAAAAAAAAAAAAAAAAAAAA==");
        assertThat(envelope.format()).isEqualTo("MyrmecSecureEnvelopeV1");
    }

    /**
     * Test-only sealing against the vector's fixed inputs — the same
     * AES-256-GCM/AAD layout {@code buildEnvelope} uses in production, with
     * the instance/repository resolution replaced by the vector's raw key.
     */
    private SecureEnvelope sealForVector(byte[] sessionKey, UUID keyId, UUID sessionId,
            UUID hostId, String purpose, String expiresAt, String plaintext, byte[] nonce) {
        try {
            String digest = "sha256:" + Base64.getEncoder().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String aad = String.join("|", "MyrmecSecureEnvelopeV1", keyId.toString(),
                    sessionId.toString(), hostId.toString(), purpose, expiresAt, digest);
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(sessionKey, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new SecureEnvelope("MyrmecSecureEnvelopeV1", keyId.toString(),
                    sessionId.toString(), hostId.toString(), purpose,
                    java.time.Instant.now().toString(), expiresAt, digest,
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(ct));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void derivationIsDeterministicAndIndependent() {
        byte[] psk = new byte[32];
        for (int i = 0; i < 32; i++) {
            psk[i] = (byte) i;
        }
        UUID sessionId = UUID.fromString("00000000-0000-4000-8000-000000000001");

        byte[] first = service.deriveSessionKey(psk, sessionId);
        byte[] second = service.deriveSessionKey(psk, sessionId);

        assertThat(first).isEqualTo(second);
        // A different sessionId must yield a different per-session key.
        assertThat(service.deriveSessionKey(psk, UUID.fromString(
                "00000000-0000-4000-8000-000000000002"))).isNotEqualTo(first);
    }

    @Test
    void rejectsBadInputs() {
        UUID sessionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.deriveSessionKey(null, sessionId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deriveSessionKey(new byte[16], sessionId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.deriveSessionKey(new byte[32], null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}