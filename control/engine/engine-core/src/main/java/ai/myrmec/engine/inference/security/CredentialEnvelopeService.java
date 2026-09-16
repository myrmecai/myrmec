// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.security;

import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.spi.crypto.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Per-session key derivation + envelope sealing for
 * {@code MyrmecSecureEnvelopeV1} (credential-envelope design §7/§8).
 *
 * <p>Secrets appear only inside {@code credentials[].envelope}; the
 * plaintext credential never crosses a method scope twice: read → digest →
 * encrypt → dropped. {@code keyId} semantics are per supervisor run: the
 * {@code pskKeyId} whose {@code host.opened} delivered the plaintext once.</p>
 *
 * <pre>
 * sessionKey = HKDF-SHA256(ikm=psk, salt=16 raw sessionId UUID bytes,
 *                          info="MyrmecSecureEnvelopeV1", len=32)
 * envelope   = AES-256-GCM(key=sessionKey, nonce=12B,
 *                          plaintext, aad="MyrmecSecureEnvelopeV1|keyId|sessionId|hostId|purpose|expiresAt|plaintextDigest")
 * ciphertext = base64(ciphertext || 16-byte tag) — the layout both Java
 *              {@code AES/GCM/NoPadding} and node
 *              {@code createDecipheriv('aes-256-gcm')} use.
 * </pre>
 *
 * <p>The salt/info/AAD contract is pinned by cross-language vectors in the
 * TypeScript SDK ({@code agents/src/security/credentialVectors.ts}); both
 * implementations MUST produce byte-identical envelopes.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CredentialEnvelopeService {

    /** HKDF info — domain separation, versioned by envelope format name. */
    public static final String HKDF_INFO = "MyrmecSecureEnvelopeV1";

    /** Envelope format name (design §8). */
    public static final String ENVELOPE_FORMAT = "MyrmecSecureEnvelopeV1";

    /** Fallback expiry when a session row has no lease ceiling. */
    private static final Duration FALLBACK_LEASE = Duration.ofHours(24);

    private static final int HKDF_LEN = 32;
    private static final byte[] INFO_BYTES = HKDF_INFO.getBytes(StandardCharsets.US_ASCII);
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;

    private final AgentHostInstanceRepository instanceRepository;
    private final SessionRepository sessionRepository;
    private final EncryptionService encryptionService;
    private final SecureRandom random = new SecureRandom();

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

    /**
     * Build one sealed {@code session.open} credential (design §8/§9): the
     * envelope carrying {@code plaintext} for {@code purpose}, addressed to
     * {@code credentialRef}. Resolves the serving instance's PSK, derives the
     * session key, and sets {@code expiresAt} to the session's lease
     * ceiling (idle-lease horizon; 24h fallback, logged, if no row).
     */
    public SecureEnvelope buildEnvelope(UUID hostInstanceId, UUID hostId, UUID sessionId,
                                        String purpose, String plaintext, byte[] fixedNonce) {
        try {
            AgentHostInstance instance = instanceRepository.findById(hostInstanceId)
                    .orElseThrow(() -> new IllegalStateException(
                            "host instance " + hostInstanceId + " not found"));
            if (instance.getPskEncrypted() == null) {
                throw new IllegalStateException(
                        "host instance " + hostInstanceId + " has no PSK (closed?)");
            }
            byte[] psk = Base64.getDecoder().decode(encryptionService.decrypt(instance.getPskEncrypted()));
            byte[] sessionKey = deriveSessionKey(psk, sessionId);

            String plaintextDigest = "sha256:" + Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(plaintext.getBytes(StandardCharsets.UTF_8)));
            String expiresAt = leaseCeiling(sessionId);
            String keyId = instance.getPskKeyId().toString();
            String sid = sessionId.toString();
            String hid = hostId.toString();
            String aad = String.join("|", ENVELOPE_FORMAT, keyId, sid, hid, purpose, expiresAt, plaintextDigest);

            byte[] nonce = (fixedNonce != null) ? fixedNonce : newNonce();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sessionKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            // GCM doFinal returns ciphertext || tag — exactly the wire layout.

            return new SecureEnvelope(
                    ENVELOPE_FORMAT, keyId, sid, hid, purpose,
                    Instant.now().toString(), expiresAt, plaintextDigest,
                    Base64.getEncoder().encodeToString(nonce),
                    Base64.getEncoder().encodeToString(ct));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("envelope sealing failed", e);
        }
    }

    /** Production path: random nonce. */
    public SecureEnvelope buildEnvelope(UUID hostInstanceId, UUID hostId, UUID sessionId,
                                        String purpose, String plaintext) {
        return buildEnvelope(hostInstanceId, hostId, sessionId, purpose, plaintext, (byte[]) null);
    }

    /** The session's lease ceiling as ISO-8601, or the 24h fallback (logged). */
    private String leaseCeiling(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId).orElse(null);
        Instant ceiling;
        if (session == null || session.getIdleLeaseExpiresAt() == null) {
            ceiling = Instant.now().plus(FALLBACK_LEASE);
            log.debug("No lease ceiling for session {} — envelope expires at +24h fallback", sessionId);
        } else {
            ceiling = session.getIdleLeaseExpiresAt();
        }
        return ceiling.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    /** 16 raw UUID bytes, big-endian (msb then lsb) — MSB first. */
    private static byte[] uuidBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    private byte[] newNonce() {
        byte[] nonce = new byte[NONCE_LEN];
        random.nextBytes(nonce);
        return nonce;
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