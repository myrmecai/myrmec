package ai.myrmec.engine.secret;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the Jackson polymorphism contract for {@link SecretPayload}.
 *
 * <p>This is the wire format used by both the REST API and the encrypted-at-rest
 * blob, so a regression here would corrupt every stored secret. Each subtype is
 * round-tripped and asserted on the discriminator value.
 */
class SecretPayloadJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsUsernamePassword() throws Exception {
        SecretPayload original = new SecretPayload.UsernamePassword("alice", "s3cret");
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"USERNAME_PASSWORD\"");
        SecretPayload parsed = mapper.readValue(json, SecretPayload.class);
        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.type()).isEqualTo(CredentialType.USERNAME_PASSWORD);
    }

    @Test
    void roundTripsBearerToken() throws Exception {
        SecretPayload original = new SecretPayload.BearerToken("test-bearer-token");
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"BEARER_TOKEN\"");
        assertThat(mapper.readValue(json, SecretPayload.class)).isEqualTo(original);
    }

    @Test
    void roundTripsApiKey() throws Exception {
        SecretPayload original = new SecretPayload.ApiKey("abc", "X-API-Key");
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"API_KEY\"");
        assertThat(mapper.readValue(json, SecretPayload.class)).isEqualTo(original);
    }

    @Test
    void roundTripsSecretKey() throws Exception {
        SecretPayload original = new SecretPayload.SecretKey("hmac-secret");
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"SECRET_KEY\"");
        assertThat(mapper.readValue(json, SecretPayload.class)).isEqualTo(original);
    }

    @Test
    void roundTripsOAuthClient() throws Exception {
        SecretPayload original = new SecretPayload.OAuthClient("client-id", "client-secret");
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"OAUTH_CLIENT\"");
        assertThat(mapper.readValue(json, SecretPayload.class)).isEqualTo(original);
    }

    @Test
    void roundTripsSslPrivateKey() throws Exception {
        SecretPayload original = new SecretPayload.SslPrivateKey(
                "-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----",
                "-----BEGIN CERTIFICATE-----\nMIIB...\n-----END CERTIFICATE-----",
                "pass");
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"SSL_PRIVATE_KEY\"");
        assertThat(mapper.readValue(json, SecretPayload.class)).isEqualTo(original);
    }

    @Test
    void roundTripsCustomPayload() throws Exception {
        SecretPayload original = new SecretPayload.CustomPayload(Map.of("foo", "bar", "n", 42));
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"CUSTOM\"");
        SecretPayload parsed = mapper.readValue(json, SecretPayload.class);
        assertThat(parsed).isInstanceOf(SecretPayload.CustomPayload.class);
        assertThat(((SecretPayload.CustomPayload) parsed).data())
                .containsEntry("foo", "bar")
                .containsEntry("n", 42);
    }

    @Test
    void typeDiscriminatorMatchesCredentialType() {
        // Every subtype's reported type() must be a valid CredentialType value.
        for (SecretPayload p : java.util.List.of(
                new SecretPayload.UsernamePassword("u", "p"),
                new SecretPayload.BearerToken("t"),
                new SecretPayload.ApiKey("k", "H"),
                new SecretPayload.SecretKey("s"),
                new SecretPayload.OAuthClient("c", "s"),
                new SecretPayload.SslPrivateKey("k", null, null),
                new SecretPayload.CustomPayload(Map.of()))) {
            assertThat(p.type()).isNotNull();
        }
    }
}
