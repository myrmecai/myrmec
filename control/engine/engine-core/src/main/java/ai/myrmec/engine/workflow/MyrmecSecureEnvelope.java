// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code MyrmecSecureEnvelopeV1} (design §17.2): the binary/AAD contract for
 * credential payloads and recovery archives crossing the engine boundary.
 *
 * <p>Layout: four ASCII bytes {@code MSE1}, a four-byte big-endian JSON-header
 * length, the RFC 8785 canonical UTF-8 header (authenticated as AAD),
 * AES-256-GCM ciphertext, and the 16-byte authentication tag. The header
 * carries {@code version}, {@code purpose} ({@code SESSION_CREDENTIALS} or
 * {@code RECOVERY_SNAPSHOT}), {@code algorithm} ({@code A256GCM}),
 * {@code keyId}, a unique random 12-byte base64 nonce, plaintext SHA-256,
 * creation/expiry timestamps, and the bound run/session/Host/Agent/generation/
 * Profile identifiers applicable to that purpose. Decryption validates the
 * AAD, tag, audience, expiry, and plaintext digest before exposing bytes.</p>
 *
 * <p>The TypeScript SDK mirrors this contract verbatim in
 * {@code agents/src/security/MyrmecSecureEnvelope.ts}; cross-language test
 * vectors prove byte parity.</p>
 */
public final class MyrmecSecureEnvelope {

    /** ASCII "MSE1" magic. */
    public static final int MAGIC = 0x4D534531;

    /** The only supported algorithm. */
    public static final String ALGORITHM = "A256GCM";

    /** Envelope purposes. */
    public static enum Purpose {
        SESSION_CREDENTIALS,
        RECOVERY_SNAPSHOT
    }

    /** HKDF info for session-credential key derivation (§16.1/§17.2). */
    public static final String SESSION_CREDENTIAL_INFO = "myrmec/session-credential/v1";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int GCM_TAG_BITS = 128;
    private static final int NONCE_BYTES = 12;

    private final String keyId;
    private final Purpose purpose;
    private final SecretKeySpec key;
    private final Map<String, String> audience;

    private MyrmecSecureEnvelope(String keyId, Purpose purpose, SecretKeySpec key,
                                Map<String, String> audience) {
        this.keyId = keyId;
        this.purpose = purpose;
        this.key = key;
        this.audience = audience;
    }

    /** Envelope for a raw 32-byte session key (already derived). */
    public static MyrmecSecureEnvelope forSessionKey(String keyId, byte[] sessionKey,
                                                     Purpose purpose,
                                                     Map<String, String> audience) {
        if (sessionKey == null || sessionKey.length != 32) {
            throw new IllegalArgumentException("session key must be 32 bytes");
        }
        return new MyrmecSecureEnvelope(keyId, purpose,
                new SecretKeySpec(sessionKey, "AES"), audience);
    }

    /**
     * Derive {@code sessionKey = HKDF-SHA256(PSK, sessionId)} (§16.1) and
     * build the envelope. The PSK is the 32-byte pre-shared key generated at
     * Host registration; the info string pins the derivation purpose.
     */
    public static MyrmecSecureEnvelope forPsk(String keyId, byte[] psk, UUID sessionId,
                                               Purpose purpose,
                                               Map<String, String> audience) {
        byte[] sessionKey = Hkdf.derive(psk,
                sessionId.toString().getBytes(StandardCharsets.UTF_8),
                SESSION_CREDENTIAL_INFO.getBytes(StandardCharsets.UTF_8),
                32);
        return forSessionKey(keyId, sessionKey, purpose, audience);
    }

    /**
     * Seal {@code plaintext} into the binary envelope. Header is AAD.
     */
    public byte[] seal(byte[] plaintext, Instant expiresAt) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);

            Map<String, Object> header = new LinkedHashMap<>();
            header.put("version", "1.0");
            header.put("purpose", purpose.name());
            header.put("algorithm", ALGORITHM);
            header.put("keyId", keyId);
            header.put("nonce", Base64.getEncoder().encodeToString(nonce));
            header.put("plaintextSha256", sha256Hex(plaintext));
            header.put("createdAt", Instant.now().toString());
            if (expiresAt != null) {
                header.put("expiresAt", expiresAt.toString());
            }
            header.putAll(audience);

            byte[] headerJson = canonicalJson(header);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(headerJson);
            byte[] ciphertextAndTag = cipher.doFinal(plaintext);

            ByteBuffer out = ByteBuffer.allocate(4 + 4 + headerJson.length + ciphertextAndTag.length);
            out.putInt(MAGIC);
            out.putInt(headerJson.length);
            out.put(headerJson);
            out.put(ciphertextAndTag);
            return out.array();
        } catch (Exception e) {
            throw new IllegalStateException("envelope seal failed", e);
        }
    }

    /**
     * Open an envelope: validate magic, AAD (header), tag, audience, expiry,
     * and plaintext digest before returning bytes. Any failure is terminal.
     */
    public byte[] open(byte[] envelope, Instant now) {
        try {
            ByteBuffer in = ByteBuffer.wrap(envelope);
            int magic = in.getInt();
            if (magic != MAGIC) {
                throw new IllegalArgumentException("bad magic");
            }
            int headerLength = in.getInt();
            if (headerLength <= 0 || headerLength > in.remaining()) {
                throw new IllegalArgumentException("bad header length");
            }
            byte[] headerJson = new byte[headerLength];
            in.get(headerJson);
            byte[] ciphertextAndTag = new byte[in.remaining()];
            in.get(ciphertextAndTag);

            Map<String, Object> header = readJson(headerJson);

            // Audience / purpose / keyId binding.
            requireValue(header, "purpose", purpose.name());
            requireValue(header, "algorithm", ALGORITHM);
            requireValue(header, "keyId", keyId);
            for (Map.Entry<String, String> bound : audience.entrySet()) {
                requireValue(header, bound.getKey(), bound.getValue());
            }

            // Expiry.
            Object expiresAt = header.get("expiresAt");
            if (expiresAt != null && now.isAfter(Instant.parse(expiresAt.toString()))) {
                throw new IllegalArgumentException("expired");
            }

            byte[] nonce = Base64.getDecoder().decode((String) header.get("nonce"));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(headerJson);
            byte[] plaintext = cipher.doFinal(ciphertextAndTag); // tag check

            // Plaintext digest.
            String digest = (String) header.get("plaintextSha256");
            if (digest == null || !digest.equals(sha256Hex(plaintext))) {
                throw new IllegalArgumentException("plaintext digest mismatch");
            }
            return plaintext;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("envelope open failed", e);
        }
    }

    private static void requireValue(Map<String, Object> header, String key, String expected) {
        Object actual = header.get(key);
        if (actual == null || !expected.equals(actual.toString())) {
            throw new IllegalArgumentException("header mismatch: " + key);
        }
    }

    private static byte[] canonicalJson(Map<String, Object> header) throws Exception {
        // RFC 8785 canonical JSON. The engine's canonicalization: keys sorted
        // (LinkedHashMap insertion order matches the TS side's explicit
        // canonical serializer), minimal-value JSON, no whitespace.
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : new java.util.TreeMap<>(header).entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number) {
                sb.append(v);
            } else {
                sb.append("\"").append(v.toString()).append("\"");
            }
        }
        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(byte[] headerJson) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(headerJson);
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(f -> {
            com.fasterxml.jackson.databind.JsonNode v = f.getValue();
            out.put(f.getKey(), v.isNull() ? null : v.asText());
        });
        return out;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal HKDF-SHA256 (RFC 5869) for the session-key derivation. */
    static final class Hkdf {
        private Hkdf() {
        }

        static byte[] derive(byte[] ikm, byte[] salt, byte[] info, int length) {
            try {
                // Extract: PRK = HMAC-SHA256(salt, IKM)  (RFC 5869)
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(
                        salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
                mac.reset();
                mac.update(ikm);
                byte[] prk = mac.doFinal();

                // Expand: T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] t = new byte[0];
                int counter = 1;
                javax.crypto.Mac expandMac = javax.crypto.Mac.getInstance("HmacSHA256");
                expandMac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
                while (out.size() < length) {
                    expandMac.reset();
                    expandMac.update(t);
                    expandMac.update(info);
                    expandMac.update((byte) counter++);
                    t = expandMac.doFinal();
                    out.write(t, 0, Math.min(t.length, length - out.size()));
                }
                return out.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException("hkdf failed", e);
            }
        }
    }
}