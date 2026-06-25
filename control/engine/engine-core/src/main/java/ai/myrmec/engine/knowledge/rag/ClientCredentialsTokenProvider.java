package ai.myrmec.engine.knowledge.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared OAuth 2.0 <strong>client-credentials</strong> token acquirer for SaaS
 * connectors that authenticate as a service principal rather than a Basic-auth
 * API token (#24 SharePoint/Microsoft Graph and future Graph/Google connectors).
 *
 * <p>This is the OSS-side seam deliberately landed ahead of the connectors that
 * consume it: the Atlassian connectors ({@link ConfluenceConnector}, {@link
 * JiraConnector}) use HTTP Basic and never touch this class, while the
 * SharePoint connector — whose drive traversal and permission-sync live in
 * Myrmec Enterprise Edition — injects this bean to obtain and refresh Graph
 * bearer tokens. Shipping the token machinery in OSS keeps the EE upgrade
 * migration-free and gives the grant a hermetic home for testing.</p>
 *
 * <p>Tokens are cached per {@code (tokenUrl, clientId, scope)} and reused until
 * shortly before they expire ({@link #REFRESH_SKEW_SECONDS}), so a long sync
 * that outlives the token lifetime transparently re-acquires one. The grant is a
 * standard {@code application/x-www-form-urlencoded} POST:</p>
 * <pre>
 * grant_type=client_credentials&amp;client_id=…&amp;client_secret=…&amp;scope=…
 * </pre>
 * <p>and the response is parsed for {@code access_token} + {@code expires_in}.
 * For Microsoft Graph the {@code tokenUrl} is
 * {@code https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token} and the
 * {@code scope} is {@code https://graph.microsoft.com/.default}.</p>
 *
 * <p>Callers MUST resolve {@code clientSecret} through {@code
 * ConnectorContext.resolveSecret} and pass the plaintext here; this class never
 * persists secrets or tokens beyond the in-memory cache.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClientCredentialsTokenProvider {

    /** Re-acquire a token this many seconds before its stated expiry. */
    static final long REFRESH_SKEW_SECONDS = 60;

    private static final int DEFAULT_EXPIRES_IN_SECONDS = 3600;

    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();

    /**
     * Parameters for a client-credentials grant. {@code scope} may be {@code
     * null}/blank for providers that infer scope from the registered app.
     */
    public record TokenRequest(
            String tokenUrl,
            String clientId,
            String clientSecret,
            String scope,
            Integer timeoutSeconds) {
    }

    /**
     * Return a valid bearer access token for the given grant, acquiring a fresh
     * one only when the cache is empty or the cached token is near expiry.
     *
     * @throws IOException          on network failure or a non-2xx token response
     * @throws InterruptedException if the calling thread is interrupted
     */
    public synchronized String accessToken(TokenRequest request) throws IOException, InterruptedException {
        if (request.tokenUrl() == null || request.tokenUrl().isBlank()) {
            throw new IOException("OAuth tokenUrl is required");
        }
        if (request.clientId() == null || request.clientId().isBlank()
                || request.clientSecret() == null || request.clientSecret().isBlank()) {
            throw new IOException("OAuth client-credentials grant requires clientId and clientSecret");
        }

        String key = cacheKey(request);
        Instant now = Instant.now();
        CachedToken cached = cache.get(key);
        if (cached != null && now.isBefore(cached.refreshAfter())) {
            return cached.token();
        }

        CachedToken fresh = requestToken(request);
        cache.put(key, fresh);
        return fresh.token();
    }

    /** Drop any cached token for this grant, forcing re-acquisition next call. */
    public synchronized void invalidate(TokenRequest request) {
        cache.remove(cacheKey(request));
    }

    private CachedToken requestToken(TokenRequest request) throws IOException, InterruptedException {
        int timeoutSeconds = request.timeoutSeconds() != null && request.timeoutSeconds() > 0
                ? request.timeoutSeconds() : 15;
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        StringJoiner form = new StringJoiner("&");
        form.add("grant_type=client_credentials");
        form.add("client_id=" + encode(request.clientId()));
        form.add("client_secret=" + encode(request.clientSecret()));
        if (request.scope() != null && !request.scope().isBlank()) {
            form.add("scope=" + encode(request.scope()));
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(request.tokenUrl()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("OAuth token endpoint returned HTTP " + response.statusCode());
        }

        JsonNode body = objectMapper.readTree(response.body());
        String token = body.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IOException("OAuth token response did not contain an access_token");
        }
        long expiresIn = body.path("expires_in").asLong(DEFAULT_EXPIRES_IN_SECONDS);
        // Refresh slightly early so an in-flight request never carries an expired token.
        long refreshIn = Math.max(1, expiresIn - REFRESH_SKEW_SECONDS);
        Instant refreshAfter = Instant.now().plusSeconds(refreshIn);
        log.debug("Acquired client-credentials token (expires_in={}s, refreshAfter={})", expiresIn, refreshAfter);
        return new CachedToken(token, refreshAfter);
    }

    private static String cacheKey(TokenRequest request) {
        return request.tokenUrl() + "|" + request.clientId() + "|"
                + (request.scope() == null ? "" : request.scope());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record CachedToken(String token, Instant refreshAfter) {
    }
}
