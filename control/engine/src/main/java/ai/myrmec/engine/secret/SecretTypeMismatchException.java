package ai.myrmec.engine.secret;

/**
 * Thrown when a consumer asks for a secret by type and the stored secret has a
 * different {@link CredentialType}. Surfaced to the API as 400 / 409 depending
 * on caller; intentionally unchecked to keep call sites readable.
 */
public class SecretTypeMismatchException extends RuntimeException {
    public SecretTypeMismatchException(String message) {
        super(message);
    }
}
