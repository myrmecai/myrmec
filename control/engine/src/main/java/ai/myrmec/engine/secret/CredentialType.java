package ai.myrmec.engine.secret;

/**
 * Shape of credential a {@link Secret} carries.
 *
 * <p>The discriminator drives both payload validation (each value maps to a
 * {@link SecretPayload} record) and how consumers interpret the decrypted
 * material at the call site. New types can be added without schema changes.
 */
public enum CredentialType {

    /** Username + password (e.g. git basic auth, DB credentials). */
    USERNAME_PASSWORD,

    /** Single opaque bearer token (PAT, OAuth access token, API token). */
    BEARER_TOKEN,

    /** Vendor API key, optionally with a custom header name. */
    API_KEY,

    /** Generic shared secret (HMAC signing keys, webhook secrets). */
    SECRET_KEY,

    /** OAuth client_id + client_secret pair. */
    OAUTH_CLIENT,

    /** SSL/SSH private key (PEM), with optional certificate / passphrase. */
    SSL_PRIVATE_KEY,

    /** Free-form JSON payload — escape hatch when no built-in type fits. */
    CUSTOM
}
