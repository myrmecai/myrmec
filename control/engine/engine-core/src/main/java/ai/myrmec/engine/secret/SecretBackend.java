package ai.myrmec.engine.secret;

/**
 * Where the cleartext payload for a {@link Secret} actually lives.
 *
 * <p>{@link #LOCAL} is the only backend wired today: payload is AES-256-GCM
 * encrypted and stored in {@code secrets.encrypted_value}. The other constants
 * are placeholders so the {@code backend} / {@code backend_ref} columns can
 * later carry external references without schema changes.
 */
public enum SecretBackend {

    /** AES-256-GCM ciphertext stored in {@code secrets.encrypted_value}. */
    LOCAL,

    /** HashiCorp Vault — {@code backend_ref} carries path/key. */
    VAULT,

    /** AWS Secrets Manager — {@code backend_ref} carries ARN/region/version. */
    AWS_SECRETS_MANAGER,

    /** Azure Key Vault — {@code backend_ref} carries vault URI + secret name. */
    AZURE_KEY_VAULT
}
