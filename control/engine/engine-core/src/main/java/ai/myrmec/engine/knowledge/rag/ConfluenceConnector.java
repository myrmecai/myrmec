package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.connector.ConnectorContext;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.EmittedChunk;
import ai.myrmec.engine.spi.connector.KnowledgeSourceConnector;
import ai.myrmec.engine.spi.connector.SourceLocator;
import ai.myrmec.engine.spi.connector.SyncResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link KnowledgeSourceConnector} (type = {@code "confluence"}) that ingests
 * pages from an Atlassian Confluence Cloud space (#24 — first SaaS connector).
 *
 * <p>It is the reference implementation for the SaaS connector family: it speaks
 * the connector SPI, resolves credentials through {@link ConnectorContext}, and
 * reuses {@link WebCrawlConnector#htmlToText(String)} for body extraction. The
 * remaining SaaS systems (Notion, Jira, SharePoint) follow this same shape with
 * their own auth + pagination.</p>
 *
 * <p>The source {@code uri} is the Confluence base URL (e.g.
 * {@code https://acme.atlassian.net/wiki}). Pages are pulled from the Cloud REST
 * API ({@code GET {base}/rest/api/content?spaceKey=…&type=page&expand=body.storage})
 * with offset pagination, and each page becomes one chunk.</p>
 *
 * <p><strong>Config JSON</strong> read from {@code knowledge_sources.config_json}:</p>
 * <pre>{@code
 * {
 *   "spaceKey": "ENG",                 // required: which space to ingest
 *   "email": "bot@acme.com",           // Basic-auth user (Atlassian account email)
 *   "apiTokenSecret": "confluence-token", // secret ref resolved via ConnectorContext
 *   "apiToken": "…",                   // inline token fallback (dev/test)
 *   "pageSize": 25,                    // results per API page (default 25, max 100)
 *   "maxPages": 500,                   // hard ceiling on pages ingested (default 500)
 *   "timeoutSeconds": 15               // per-request timeout (default 15)
 * }
 * }</pre>
 *
 * <p>Auth is HTTP Basic with {@code email:apiToken} (Atlassian Cloud's API-token
 * scheme), so no OAuth dance is required. A per-page parse failure is recorded
 * in {@link SyncResult#errors()} (PARTIAL); a failure listing the first page
 * (auth/space error) is non-recoverable and throws {@link ConnectorException}.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ConfluenceConnector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "confluence";

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_MAX_PAGES = 500;
    private static final int HARD_PAGE_LIMIT = 10_000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        ConfluenceConfig config = parseConfig(locator.config().get("raw"));
        String baseUrl = normaliseBase(locator.uri());
        if (config.spaceKey() == null || config.spaceKey().isBlank()) {
            throw new ConnectorException("confluence source requires a 'spaceKey' in config");
        }

        String authHeader = buildAuthHeader(config, context);
        int pageSize = clamp(config.pageSize(), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        int maxPages = Math.min(positiveOr(config.maxPages(), DEFAULT_MAX_PAGES), HARD_PAGE_LIMIT);
        int timeoutSeconds = positiveOr(config.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS);
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        List<String> errors = new ArrayList<>();
        long emitted = 0;
        int start = 0;
        boolean firstPage = true;

        while (emitted < maxPages) {
            JsonNode response;
            try {
                response = fetchContentPage(httpClient, baseUrl, config.spaceKey(),
                        start, pageSize, authHeader, timeoutSeconds);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (firstPage) {
                    throw new ConnectorException(
                            "Failed to list Confluence space '" + config.spaceKey() + "': " + e.getMessage(), e);
                }
                errors.add("page fetch failed at start=" + start + ": " + e.getMessage());
                break;
            }
            firstPage = false;

            JsonNode results = response.path("results");
            if (!results.isArray() || results.isEmpty()) {
                break;
            }
            for (JsonNode page : results) {
                if (emitted >= maxPages) {
                    break;
                }
                EmittedChunk chunk = toChunk(baseUrl, config.spaceKey(), page, errors);
                if (chunk != null) {
                    context.chunkSink().accept(chunk);
                    emitted++;
                }
            }
            int returned = results.size();
            if (returned < pageSize) {
                break; // last page
            }
            start += returned;
        }

        log.info("confluence sync emitted {} page chunk(s) from space {} ({} error(s))",
                emitted, config.spaceKey(), errors.size());
        SyncResult.Status status = errors.isEmpty() ? SyncResult.Status.SUCCESS : SyncResult.Status.PARTIAL;
        Instant completedAt = Instant.now();
        return new SyncResult(status, emitted, errors, Duration.between(startedAt, completedAt), completedAt);
    }

    // --- fetch / transform ---------------------------------------------------

    private JsonNode fetchContentPage(HttpClient httpClient, String baseUrl, String spaceKey,
                                      int start, int limit, String authHeader, int timeoutSeconds)
            throws IOException, InterruptedException {
        String url = baseUrl + "/rest/api/content"
                + "?spaceKey=" + encode(spaceKey)
                + "&type=page"
                + "&status=current"
                + "&start=" + start
                + "&limit=" + limit
                + "&expand=" + encode("body.storage,version");
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private EmittedChunk toChunk(String baseUrl, String spaceKey, JsonNode page, List<String> errors) {
        try {
            String pageId = page.path("id").asText(null);
            if (pageId == null) {
                return null;
            }
            String title = page.path("title").asText("");
            String storage = page.path("body").path("storage").path("value").asText("");
            String text = WebCrawlConnector.htmlToText(storage);
            if (text.isBlank() && title.isBlank()) {
                return null;
            }
            String body = title.isBlank() ? text : (title + "\n\n" + text);
            String locator = spaceKey + "/" + pageId;

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("spaceKey", spaceKey);
            metadata.put("pageId", pageId);
            if (!title.isBlank()) {
                metadata.put("title", title);
            }
            String webui = page.path("_links").path("webui").asText(null);
            if (webui != null && !webui.isBlank()) {
                metadata.put("url", baseUrl + webui);
            }
            return new EmittedChunk(locator, body, metadata);
        } catch (RuntimeException e) {
            errors.add("page parse error: " + e.getMessage());
            return null;
        }
    }

    // --- helpers -------------------------------------------------------------

    private String buildAuthHeader(ConfluenceConfig config, ConnectorContext context) throws ConnectorException {
        String token = resolveToken(config, context);
        if (token == null || token.isBlank()) {
            throw new ConnectorException("confluence source requires an API token (apiTokenSecret or apiToken)");
        }
        String email = config.email() == null ? "" : config.email();
        String credentials = email + ":" + token;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String resolveToken(ConfluenceConfig config, ConnectorContext context) {
        if (config.apiTokenSecret() != null && !config.apiTokenSecret().isBlank()) {
            String resolved = context.resolveSecret(config.apiTokenSecret());
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return config.apiToken();
    }

    private String normaliseBase(String uri) throws ConnectorException {
        if (uri == null || uri.isBlank()) {
            throw new ConnectorException("confluence source uri (base URL) is required");
        }
        String trimmed = uri.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI parsed = new URI(trimmed);
            String scheme = parsed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ConnectorException("confluence base URL must be http(s): " + uri);
            }
        } catch (URISyntaxException e) {
            throw new ConnectorException("Invalid confluence base URL: " + uri, e);
        }
        return trimmed;
    }

    private ConfluenceConfig parseConfig(String rawJson) throws ConnectorException {
        if (rawJson == null || rawJson.isBlank()) {
            return ConfluenceConfig.EMPTY;
        }
        try {
            return objectMapper.readValue(rawJson, ConfluenceConfig.class);
        } catch (IOException e) {
            throw new ConnectorException("Invalid confluence source config JSON: " + e.getMessage(), e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static int positiveOr(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private static int clamp(Integer value, int fallback, int min, int max) {
        int v = value != null && value > 0 ? value : fallback;
        return Math.max(min, Math.min(v, max));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConfluenceConfig(
            String spaceKey,
            String email,
            String apiToken,
            String apiTokenSecret,
            Integer pageSize,
            Integer maxPages,
            Integer timeoutSeconds) {

        static final ConfluenceConfig EMPTY =
                new ConfluenceConfig(null, null, null, null, null, null, null);
    }
}
