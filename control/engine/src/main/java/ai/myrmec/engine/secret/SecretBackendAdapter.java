package ai.myrmec.engine.secret;

/**
 * Pluggable persistence + retrieval strategy for secret payloads.
 *
 * <p>Today only {@link LocalSecretBackendAdapter} is implemented. Future Vault /
 * cloud Secret Manager adapters slot in here without touching callers.
 */
public interface SecretBackendAdapter {

    SecretBackend backend();

    /**
     * Persist the payload for {@code secret}. For local storage this means
     * writing the AES-GCM ciphertext into {@link Secret#setEncryptedValue}.
     * For external backends the implementation pushes to the remote vault and
     * populates {@link Secret#setBackendRef} with the lookup coordinates.
     */
    void write(Secret secret, SecretPayload payload);

    /** Retrieve and decrypt / fetch the payload. */
    SecretPayload read(Secret secret);

    /** Best-effort cleanup of any external state owned by the backend. */
    default void delete(Secret secret) {
        // no-op for local; remote backends should override
    }
}
