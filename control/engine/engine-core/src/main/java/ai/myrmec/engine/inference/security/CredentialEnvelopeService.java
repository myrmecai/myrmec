// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Per-session key derivation for {@code MyrmecSecureEnvelopeV1}
 * (credential-envelope design §7):
 *
 * <pre>
 * sessionKey = HKDF-SHA256(
 *   ikm  = psk,                       // 32 bytes — this run's key
 *   salt = sessionId,                 // 16 raw UUID bytes, MSB first
 *   info = "MyrmecSecureEnvelopeV1",  // ASCII, versioned format name
 *   len  = 32)
 * </pre>
 *
 * <p>The salt/info contract is pinned by cross-language vectors in the
 * TypeScript SDK ({@code agents/src/security/credentialVectors.ts}); both
 * implementations MUST produce byte-identical keys. Java has no HKDF in the
 * JDK, so the RFC 5869 extract-then-expand loop is implemented here against
 * {@code javax.crypto.Mac} — no new dependency.</p>
 *
 * <p>Skeleton for Phase-1 step 2: {@code encrypt(instance, sessionId,
 * purpose, plaintext)} (envelope sealing used by
 * {@code SessionContextAssembler}) lands in the next task; this class ships
 * the derivation contract only.</p>
 */
public class CredentialEnvelopeService {

    /** HKDF info — domain separation, versioned by envelope format name. */
    public static final String HKDF_INFO = "MyrmecSecureEnvelopeV1";

    private static final int HKDF_LEN = 32;

    private static final byte[] INFO_BYTES = HKDF_INFO.getBytes(StandardCharsets.US_ASCII);

    /**
     * Derive the per-session key: HKDF-SHA256 with the 16 raw UUID bytes
     * (MSB first, java {@code UUID.toString()} order) as salt.
     */
    public byte[] deriveSessionKey(byte[] psk, UUID sessionId) {
        if (psk == null || psk.length != 32) {
            throw new IllegalArgumentException("psk must be 32 bytes");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId is required");
        }
        byte[] salt = uuidBytes(sessionId);
        return hkdfSha256(psk, salt, INFO_BYTES, HKDF_LEN);
    }

    /** 16 raw UUID bytes, big-endian (msb then lsb) — MSB first. */
    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    /**
     * HKDF-SHA256 (RFC 5869): PRK = HMAC-SHA256(salt, ikm), then
     * T(i) = HMAC-SHA256(PRK, T(i-1) || info || i).
     */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac extract = Mac.getInstance("HmacSHA256");
            extract.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = extract.doFinal(ikm);

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] t = new byte[0];
            int counter = 1;
            Mac expand = Mac.getInstance("HmacSHA256");
            expand.init(new SecretKeySpec(prk, "HmacSHA256"));
            while (out.size() < length) {
                expand.reset();
                expand.update(t);
                expand.update(info);
                expand.update((byte) counter++);
                t = expand.doFinal();
                out.write(t, 0, Math.min(t.length, length - out.size()));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("hkdf-sha256 failed", e);
        }
    }
}