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
 * The cross-language derivation contract (credential-envelope design §7):
 * {@code sessionKey = HKDF-SHA256(ikm=psk, salt=raw sessionId bytes,
 * info="MyrmecSecureEnvelopeV1", len=32)}. The vector below is the SINGLE
 * SOURCE OF TRUTH shared with the TypeScript SDK
 * ({@code agents/src/security/credentialVectors.ts}, VECTOR_1) — both
 * implementations MUST produce byte-identical keys.
 */
class CredentialEnvelopeServiceTest {

    private final CredentialEnvelopeService service = new CredentialEnvelopeService();

    /** VECTOR_1 from credentialVectors.ts — the shared contract fixture. */
    @Test
    void deriveSessionKeyMatchesCrossLanguageVector() {
        byte[] psk = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh4=");
        UUID sessionId = UUID.fromString("12345678-90ab-4cde-9f01-234567890abc");

        byte[] sessionKey = service.deriveSessionKey(psk, sessionId);

        assertThat(HexFormat.of().formatHex(sessionKey))
                .isEqualTo("8edc74d2b2d914fc2028390a7f181786865fb9c8acac5f5e498c911cc6307848");
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