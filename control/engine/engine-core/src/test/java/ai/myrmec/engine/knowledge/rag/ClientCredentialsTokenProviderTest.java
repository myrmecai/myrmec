package ai.myrmec.engine.knowledge.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for {@link ClientCredentialsTokenProvider} — the OSS OAuth seam that
 * the EE SharePoint connector consumes. Drives the grant against a loopback
 * token endpoint; no Spring context required.
 */
class ClientCredentialsTokenProviderTest {

    private ClientCredentialsTokenProvider provider;
    private HttpServer server;
    private String tokenUrl;

    private final AtomicInteger tokenRequests = new AtomicInteger();
    private volatile int statusCode = 200;
    private volatile String responseBody =
            "{\"access_token\":\"tok-abc\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    @BeforeEach
    void setUp() throws IOException {
        provider = new ClientCredentialsTokenProvider(new ObjectMapper());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/token";
        server.createContext("/oauth2/token", this::handleToken);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void acquiresBearerTokenFromClientCredentialsGrant() throws Exception {
        String token = provider.accessToken(request());

        assertThat(token).isEqualTo("tok-abc");
        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    @Test
    void cachesTokenAcrossCalls() throws Exception {
        provider.accessToken(request());
        provider.accessToken(request());
        provider.accessToken(request());

        assertThat(tokenRequests.get()).isEqualTo(1);
    }

    @Test
    void invalidateForcesReacquisition() throws Exception {
        provider.accessToken(request());
        provider.invalidate(request());
        responseBody = "{\"access_token\":\"tok-xyz\",\"expires_in\":3600}";

        String token = provider.accessToken(request());

        assertThat(token).isEqualTo("tok-xyz");
        assertThat(tokenRequests.get()).isEqualTo(2);
    }

    @Test
    void missingCredentialsThrows() {
        var bad = new ClientCredentialsTokenProvider.TokenRequest(
                tokenUrl, null, "secret", "scope", 5);
        assertThatThrownBy(() -> provider.accessToken(bad)).isInstanceOf(IOException.class);
    }

    @Test
    void nonSuccessResponseThrows() {
        statusCode = 401;
        responseBody = "{\"error\":\"invalid_client\"}";
        assertThatThrownBy(() -> provider.accessToken(request())).isInstanceOf(IOException.class);
    }

    @Test
    void responseWithoutAccessTokenThrows() {
        responseBody = "{\"token_type\":\"Bearer\",\"expires_in\":3600}";
        assertThatThrownBy(() -> provider.accessToken(request())).isInstanceOf(IOException.class);
    }

    private ClientCredentialsTokenProvider.TokenRequest request() {
        return new ClientCredentialsTokenProvider.TokenRequest(
                tokenUrl, "client-1", "client-secret", "https://graph.microsoft.com/.default", 5);
    }

    private void handleToken(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
