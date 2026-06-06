package ai.myrmec.engine.secret;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Map;

/**
 * Typed cleartext payload for a {@link Secret}.
 *
 * <p>One subtype per {@link CredentialType}. Serialized to / from the encrypted
 * blob as JSON with a {@code type} discriminator so the on-disk form is
 * self-describing. The on-wire form (REST request body) reuses the same
 * polymorphism — the API never persists a {@link CredentialType} without a
 * matching payload subtype.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", visible = true)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = "type", allowGetters = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = SecretPayload.UsernamePassword.class, name = "USERNAME_PASSWORD"),
        @JsonSubTypes.Type(value = SecretPayload.BearerToken.class,      name = "BEARER_TOKEN"),
        @JsonSubTypes.Type(value = SecretPayload.ApiKey.class,           name = "API_KEY"),
        @JsonSubTypes.Type(value = SecretPayload.SecretKey.class,        name = "SECRET_KEY"),
        @JsonSubTypes.Type(value = SecretPayload.OAuthClient.class,      name = "OAUTH_CLIENT"),
        @JsonSubTypes.Type(value = SecretPayload.SslPrivateKey.class,    name = "SSL_PRIVATE_KEY"),
        @JsonSubTypes.Type(value = SecretPayload.CustomPayload.class,    name = "CUSTOM"),
})
public sealed interface SecretPayload {

    CredentialType type();

    record UsernamePassword(String username, String password) implements SecretPayload {
        @Override public CredentialType type() { return CredentialType.USERNAME_PASSWORD; }
    }

    record BearerToken(String token) implements SecretPayload {
        @Override public CredentialType type() { return CredentialType.BEARER_TOKEN; }
    }

    /** {@code header} is optional; defaults to {@code Authorization}/{@code X-API-Key} at the call site. */
    record ApiKey(String key, String header) implements SecretPayload {
        @Override public CredentialType type() { return CredentialType.API_KEY; }
    }

    record SecretKey(String secret) implements SecretPayload {
        @Override public CredentialType type() { return CredentialType.SECRET_KEY; }
    }

    record OAuthClient(String clientId, String clientSecret) implements SecretPayload {
        @Override public CredentialType type() { return CredentialType.OAUTH_CLIENT; }
    }

    /** {@code certificate} and {@code passphrase} are optional. */
    record SslPrivateKey(String privateKey, String certificate, String passphrase) implements SecretPayload {
        @Override public CredentialType type() { return CredentialType.SSL_PRIVATE_KEY; }
    }

    record CustomPayload(Map<String, Object> data) implements SecretPayload {
        @Override public CredentialType type() { return CredentialType.CUSTOM; }
    }
}
