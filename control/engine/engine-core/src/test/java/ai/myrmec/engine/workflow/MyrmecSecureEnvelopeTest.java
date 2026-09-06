// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code MyrmecSecureEnvelopeV1} (design §17.2) — cross-language parity
 * tests. The HKDF vector and the TS-sealed fixture hex are shared with
 * {@code agents/src/security/MyrmecSecureEnvelope.test.ts}: a TS-sealed
 * envelope must open here, proving byte-level contract parity.
 */
@DisplayName("F10: MyrmecSecureEnvelopeV1 (§17.2)")
class MyrmecSecureEnvelopeTest {

    private static final byte[] FIXTURE_KEY = new byte[32];
    private static final String FIXTURE_SESSION_ID = "11111111-1111-1111-1111-111111111111";
    private static final byte[] FIXTURE_PLAINTEXT =
            "myrmec session credential vector".getBytes();

    static {
        java.util.Arrays.fill(FIXTURE_KEY, (byte) 0xAB);
    }

    private MyrmecSecureEnvelope fixtureEnvelope() {
        return MyrmecSecureEnvelope.forPsk("k1", FIXTURE_KEY,
                UUID.fromString(FIXTURE_SESSION_ID),
                MyrmecSecureEnvelope.Purpose.SESSION_CREDENTIALS,
                Map.of("sessionId", FIXTURE_SESSION_ID));
    }

    @Test
    @DisplayName("HKDF session-key derivation matches the TS vector")
    void hkdfMatchesTsVector() throws Exception {
        byte[] sessionKey = MyrmecSecureEnvelope.Hkdf.derive(
                FIXTURE_KEY,
                FIXTURE_SESSION_ID.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                MyrmecSecureEnvelope.SESSION_CREDENTIAL_INFO
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                32);
        // Shared cross-language vector (TS MyrmecSecureEnvelope.test.ts pins
        // the same value).
        assertThat(HexFormat.of().formatHex(sessionKey)).isEqualTo(
                "fdbe3883fd4add0a753d59a7a87625554376b0d1558786e7724a6998fac4cc9f");
    }

    @Test
    @DisplayName("seal → open round-trip")
    void sealOpenRoundTrip() {
        MyrmecSecureEnvelope envelope = fixtureEnvelope();
        byte[] sealed = envelope.seal(FIXTURE_PLAINTEXT, null);
        byte[] opened = envelope.open(sealed, Instant.now());
        assertThat(opened).isEqualTo(FIXTURE_PLAINTEXT);
    }

    @Test
    @DisplayName("the TS-sealed fixture opens in Java (cross-language parity)")
    void tsSealedFixtureOpensInJava() throws Exception {
        // Sealed by the TS emit-envelope-fixture test with the same PSK,
        // sessionId, keyId, purpose, audience, and plaintext; pinned as a
        // test resource so the exact bytes travel without hand-transcription.
        String fixtureJson = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of(getClass().getResource(
                        "/orchestration/envelope-fixture.json").toURI())),
                java.nio.charset.StandardCharsets.UTF_8);
        String tsSealedHex = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(fixtureJson).get("tsSealedHex").asText();
        byte[] sealed = HexFormat.of().parseHex(tsSealedHex);
        MyrmecSecureEnvelope envelope = fixtureEnvelope();
        byte[] opened = envelope.open(sealed, Instant.now());
        assertThat(opened).isEqualTo(FIXTURE_PLAINTEXT);
    }

    @Test
    @DisplayName("wrong audience fails closed")
    void wrongAudienceFails() {
        MyrmecSecureEnvelope envelope = fixtureEnvelope();
        byte[] sealed = envelope.seal(FIXTURE_PLAINTEXT, null);
        MyrmecSecureEnvelope wrongAudience = MyrmecSecureEnvelope.forPsk(
                "k1", FIXTURE_KEY, UUID.fromString(FIXTURE_SESSION_ID),
                MyrmecSecureEnvelope.Purpose.SESSION_CREDENTIALS,
                Map.of("sessionId", "22222222-2222-2222-2222-222222222222"));
        assertThatThrownBy(() -> wrongAudience.open(sealed, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("expired envelope fails closed")
    void expiredFails() {
        MyrmecSecureEnvelope envelope = fixtureEnvelope();
        byte[] sealed = envelope.seal(FIXTURE_PLAINTEXT,
                Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThatThrownBy(() -> envelope.open(sealed, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("tampered ciphertext fails closed (GCM tag)")
    void tamperedFails() {
        MyrmecSecureEnvelope envelope = fixtureEnvelope();
        byte[] sealed = envelope.seal(FIXTURE_PLAINTEXT, null);
        sealed[sealed.length - 20] ^= (byte) 0xFF;
        assertThatThrownBy(() -> envelope.open(sealed, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("magic bytes and layout are the §17.2 contract")
    void magicAndLayout() {
        MyrmecSecureEnvelope envelope = fixtureEnvelope();
        byte[] sealed = envelope.seal(FIXTURE_PLAINTEXT, null);
        // MSE1 magic
        assertThat(sealed[0]).isEqualTo((byte) 'M');
        assertThat(sealed[1]).isEqualTo((byte) 'S');
        assertThat(sealed[2]).isEqualTo((byte) 'E');
        assertThat(sealed[3]).isEqualTo((byte) '1');
        // 4-byte BE header length sanity
        int headerLength = ((sealed[4] & 0xFF) << 24) | ((sealed[5] & 0xFF) << 16)
                | ((sealed[6] & 0xFF) << 8) | (sealed[7] & 0xFF);
        assertThat(headerLength).isPositive().isLessThan(sealed.length);
    }
}