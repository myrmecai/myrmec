package ai.myrmec.engine._system.crypto;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Service for encrypting and decrypting sensitive data using AES-256-GCM.
 */
@Service
@Slf4j
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final SecretKey encryptionKey;
    private final List<SecretKey> decryptionKeys;

    public EncryptionService(
            @Value("${myrmec.encryption.key:default-encryption-key-change-me}") String key,
            @Value("${myrmec.encryption.previous-keys:}") String previousKeysCsv
    ) {
        this.encryptionKey = deriveKey(key);
        this.decryptionKeys = buildDecryptionKeys(key, previousKeysCsv);
    }

    /**
     * Encrypt plaintext to bytes.
     * Format: [IV (12 bytes)][encrypted data with auth tag]
     */
    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, parameterSpec);

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to encrypted data
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + encrypted.length);
            buffer.put(iv);
            buffer.put(encrypted);
            return buffer.array();
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new RuntimeException("Encryption failed", e);
        }
    }

    /**
     * Decrypt bytes to plaintext.
     */
    public String decrypt(byte[] ciphertext) {
        if (ciphertext == null || ciphertext.length <= GCM_IV_LENGTH) {
            throw new RuntimeException("Decryption failed: invalid ciphertext");
        }

        ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
        byte[] iv = new byte[GCM_IV_LENGTH];
        buffer.get(iv);

        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);

        Exception lastException = null;
        int keyIndex = 0;

        for (SecretKey key : decryptionKeys) {
            try {
                String value = decryptWithKey(iv, encrypted, key);
                if (keyIndex > 0) {
                    log.info("Decrypted value with fallback encryption key index {}", keyIndex);
                }
                return value;
            } catch (Exception e) {
                lastException = e;
                keyIndex++;
            }
        }

        log.error("Decryption failed with all configured encryption keys", lastException);
        throw new RuntimeException("Decryption failed", lastException);
    }

    private String decryptWithKey(byte[] iv, byte[] encrypted, SecretKey key) throws Exception {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw e;
        }
    }

    /**
     * Derive a 256-bit key from the provided string using SHA-256.
     */
    private SecretKey deriveKey(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(Arrays.copyOf(hash, 32), "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive key", e);
        }
    }

    private List<SecretKey> buildDecryptionKeys(String currentKey, String previousKeysCsv) {
        Stream<String> current = Stream.of(currentKey);
        Stream<String> previous = previousKeysCsv == null || previousKeysCsv.isBlank()
                ? Stream.empty()
                : Arrays.stream(previousKeysCsv.split("[,;]"));

        return Stream.concat(current, previous)
                .map(String::trim)
                .filter(k -> !k.isBlank())
                .distinct()
                .map(this::deriveKey)
                .toList();
    }
}
