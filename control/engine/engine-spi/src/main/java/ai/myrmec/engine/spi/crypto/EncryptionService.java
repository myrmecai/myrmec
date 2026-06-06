package ai.myrmec.engine.spi.crypto;

/**
 * Symmetric encryption SPI for engine-managed sensitive payloads (API keys,
 * connector secrets, embedded credentials).
 *
 * <p>The Community implementation derives a 256-bit key from
 * {@code myrmec.encryption.key} via SHA-256 and uses AES-256-GCM. Enterprise
 * implementations may delegate to an HSM, cloud KMS, or envelope-encrypt with
 * data keys without changing call sites.
 *
 * <p>Implementations must be thread-safe; the engine invokes them concurrently
 * from request threads, scheduled jobs, and migration runners.
 */
public interface EncryptionService {

    /**
     * Encrypt {@code plaintext} into an opaque byte array safe for persistence.
     * Returned bytes include any IV / nonce / metadata the implementation needs
     * to reverse the operation.
     */
    byte[] encrypt(String plaintext);

    /**
     * Decrypt bytes previously produced by {@link #encrypt(String)}.
     * Implementations may attempt key rotation by trying a configured set of
     * previous keys when the active key fails.
     *
     * @throws RuntimeException when the ciphertext is null, truncated, or cannot
     *                          be decrypted with any configured key.
     */
    String decrypt(byte[] ciphertext);
}
