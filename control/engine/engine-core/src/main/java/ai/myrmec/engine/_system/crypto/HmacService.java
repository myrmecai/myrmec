// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 helper for hash-chain computation.
 *
 * <p>No HMAC service existed in the engine prior to this — only plain
 * SHA-256 (in {@link BasicEncryptionService}) and AES-GCM (in
 * {@link BasicEncryptionService}). This class provides the keyed-hash
 * primitive needed by {@link ai.myrmec.engine.audit.AuditHashChainService}.
 *
 * <p>The key is resolved by the caller from the secrets vault via
 * {@link ai.myrmec.engine.secret.SecretResolverService}. This class is
 * stateless and does not manage the key lifecycle.
 */
@Component
public final class HmacService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Compute HMAC-SHA256 over the given data using the provided key.
     *
     * @param key  the HMAC key (raw bytes)
     * @param data the data to hash
     * @return 64-character lowercase hex string of the HMAC
     */
    public String hmacSha256Hex(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        } catch (InvalidKeyException e) {
            throw new IllegalArgumentException("Invalid HMAC key", e);
        }
    }

    /**
     * Compute HMAC-SHA256 over the concatenation of two byte arrays.
     * Convenience method for {@code HMAC(key, a ‖ b)}.
     *
     * @param key the HMAC key (raw bytes)
     * @param a   first data segment
     * @param b   second data segment
     * @return 64-character lowercase hex string of the HMAC
     */
    public String hmacSha256Hex(byte[] key, byte[] a, byte[] b) {
        byte[] combined = new byte[a.length + b.length];
        System.arraycopy(a, 0, combined, 0, a.length);
        System.arraycopy(b, 0, combined, a.length, b.length);
        return hmacSha256Hex(key, combined);
    }

    /**
     * Convert a hex string to raw bytes.
     *
     * @param hex a lowercase hex string (e.g. a hash output)
     * @return the corresponding byte array
     */
    public byte[] fromHex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}