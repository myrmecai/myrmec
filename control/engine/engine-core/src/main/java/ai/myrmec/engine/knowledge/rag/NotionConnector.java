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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link KnowledgeSourceConnector} (type = {@code "notion"}) that ingests pages
 * from a Notion workspace (#24 — third SaaS connector).
 *
 * <p>Unlike the Atlassian connectors ({@link ConfluenceConnector}, {@link
 * JiraConnector}), Notion is <strong>not</strong> Basic-auth and its content is
 * <strong>not</strong> a single HTML/ADF field. This connector targets the
 * common <em>internal integration</em> path: a single long-lived bearer token
 * (no OAuth exchange or refresh), so it reuses the same secret-resolution shape
 * as the other connectors without touching {@link ClientCredentialsTokenProvider}.
 * Public-OAuth (authorization-code) distribution for multi-workspace apps is a
 * later enhancement.</p>
 *
 * <p>Notion's API is block-based and cursor-paginated:</p>
 * <ul>
 *   <li>Pages are discovered via {@code POST {base}/v1/search} (filtered to
 *       {@code object = page}) using {@code start_cursor}/{@code has_more}.</li>
 *   <li>Each page's text is assembled by walking {@code GET
 *       {base}/v1/blocks/{id}/children} recursively and flattening the rich-text
 *       spans into plain text via {@link #blocksToText}.</li>
 * </ul>
 *
 * <p>The source {@code uri} is the API base URL (normally
 * {@code https://api.notion.com}). Each Notion page becomes one chunk.</p>
 *
 * <p><strong>Config JSON</strong> read from {@code knowledge_sources.config_json}:</p>
 * <pre>{@code
 * {
 *   "token": "secret_…",            // inline internal-integration token (dev/test)
 *   "tokenSecret": "notion-token",  // secret ref resolved via ConnectorContext
 *   "query": "engineering",         // optional search query to scope pages
 *   "notionVersion": "2022-06-28",  // Notion-Version header (default 2022-06-28)
 *   "pageSize": 100,                // results per search page (default 100, max 100)
 *   "maxPages": 500,                // hard ceiling on pages ingested (default 500)
 *   "maxBlocksPerPage": 5000,       // safety cap on blocks walked per page
 *   "timeoutSeconds": 15            // per-request timeout (default 15)
 * }
 * }</pre>
 *
 * <p>A failure on the first search page (auth/version error) is non-recoverable
 * and throws {@link ConnectorException}; a per-page block-fetch or parse failure
 * is recorded in {@link SyncResult#errors()} (PARTIAL).</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotionConnector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "notion";

    private static final String DEFAULT_NOTION_VERSION = "2022-06-28";
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_MAX_PAGES = 500;
    private static final int HARD_PAGE_LIMIT = 100_000;
    private static final int DEFAULT_MAX_BLOCKS_PER_PAGE = 5_000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    /** Guard against pathological self-referential block trees. */
    private static final int MAX_BLOCK_DEPTH = 30;

    /** Notion block types that carry a {@code rich_text} array we ingest. */
    private static final Set<String> RICH_TEXT_BLOCKS = Set.of(
            "paragraph", "heading_1", "heading_2", "heading_3",
            "bulleted_list_item", "numbered_list_item", "to_do", "toggle",
            "quote", "callout", "code");

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        NotionConfig config = parseConfig(locator.config().get("raw"));
        String baseUrl = normaliseBase(locator.uri());

        String token = resolveToken(config, context);
        if (token == null || token.isBlank()) {
            throw new ConnectorException("notion source requires a token (tokenSecret or token)");
        }
        String notionVersion = config.notionVersion() == null || config.notionVersion().isBlank()
                ? DEFAULT_NOTION_VERSION : config.notionVersion();
        int pageSize = clamp(config.pageSize(), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        int maxPages = Math.min(positiveOr(config.maxPages(), DEFAULT_MAX_PAGES), HARD_PAGE_LIMIT);
        int maxBlocks = positiveOr(config.maxBlocksPerPage(), DEFAULT_MAX_BLOCKS_PER_PAGE);
        int timeoutSeconds = positiveOr(config.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS);
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        List<String> errors = new ArrayList<>();
        long emitted = 0;
        String cursor = null;
        boolean firstPage = true;

        while (emitted < maxPages) {
            JsonNode response;
            try {
                response = fetchSearchPage(httpClient, baseUrl, config.query(), cursor, pageSize,
                        token, notionVersion, timeoutSeconds);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (firstPage) {
                    throw new ConnectorException("Failed to search Notion workspace: " + e.getMessage(), e);
                }
                errors.add("search fetch failed at cursor=" + cursor + ": " + e.getMessage());
                break;
            }
            firstPage = false;

            JsonNode results = response.path("results");
            if (results.isArray() && !results.isEmpty()) {
                for (JsonNode page : results) {
                    if (emitted >= maxPages) {
                        break;
                    }
                    if (!"page".equals(page.path("object").asText(""))) {
                        continue;
                    }
                    EmittedChunk chunk = toChunk(httpClient, baseUrl, page, token, notionVersion,
                            timeoutSeconds, maxBlocks, errors);
                    if (chunk != null) {
                        context.chunkSink().accept(chunk);
                        emitted++;
                    }
                }
            }

            if (!response.path("has_more").asBoolean(false)) {
                break;
            }
            String next = response.path("next_cursor").asText(null);
            if (next == null || next.isBlank()) {
                break;
            }
            cursor = next;
        }

        log.info("notion sync emitted {} page chunk(s) ({} error(s))", emitted, errors.size());
        SyncResult.Status status = errors.isEmpty() ? SyncResult.Status.SUCCESS : SyncResult.Status.PARTIAL;
        Instant completedAt = Instant.now();
        return new SyncResult(status, emitted, errors, Duration.between(startedAt, completedAt), completedAt);
    }

    // --- fetch / transform ---------------------------------------------------

    private JsonNode fetchSearchPage(HttpClient httpClient, String baseUrl, String query, String cursor,
                                     int pageSize, String token, String notionVersion, int timeoutSeconds)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder("{");
        body.append("\"filter\":{\"property\":\"object\",\"value\":\"page\"},");
        body.append("\"page_size\":").append(pageSize);
        if (query != null && !query.isBlank()) {
            body.append(",\"query\":").append(jsonString(query));
        }
        if (cursor != null && !cursor.isBlank()) {
            body.append(",\"start_cursor\":").append(jsonString(cursor));
        }
        body.append("}");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/search"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", notionVersion)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private EmittedChunk toChunk(HttpClient httpClient, String baseUrl, JsonNode page, String token,
                                 String notionVersion, int timeoutSeconds, int maxBlocks, List<String> errors) {
        try {
            String pageId = page.path("id").asText(null);
            if (pageId == null) {
                return null;
            }
            String title = extractTitle(page);

            StringBuilder text = new StringBuilder();
            int[] budget = {maxBlocks};
            collectBlocks(httpClient, baseUrl, pageId, token, notionVersion, timeoutSeconds, budget, 0, text, errors);

            String bodyText = text.toString().strip().replaceAll("\n{3,}", "\n\n");
            String content = title.isBlank() ? bodyText : (bodyText.isBlank() ? title : title + "\n\n" + bodyText);
            if (content.isBlank()) {
                return null;
            }

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("pageId", pageId);
            if (!title.isBlank()) {
                metadata.put("title", title);
            }
            String url = page.path("url").asText(null);
            if (url != null && !url.isBlank()) {
                metadata.put("url", url);
            }
            return new EmittedChunk(pageId, content, metadata);
        } catch (RuntimeException e) {
            errors.add("page parse error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Walk a page/block's children depth-first, appending each block's plain
     * text to {@code out}. Recurses into blocks with {@code has_children},
     * bounded by {@code budget} (remaining blocks) and {@link #MAX_BLOCK_DEPTH}.
     */
    private void collectBlocks(HttpClient httpClient, String baseUrl, String blockId, String token,
                               String notionVersion, int timeoutSeconds, int[] budget, int depth,
                               StringBuilder out, List<String> errors) {
        if (depth > MAX_BLOCK_DEPTH || budget[0] <= 0) {
            return;
        }
        String cursor = null;
        while (budget[0] > 0) {
            JsonNode response;
            try {
                response = fetchBlockChildren(httpClient, baseUrl, blockId, cursor, token,
                        notionVersion, timeoutSeconds);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                errors.add("block fetch failed for " + blockId + ": " + e.getMessage());
                return;
            }
            JsonNode results = response.path("results");
            if (!results.isArray() || results.isEmpty()) {
                break;
            }
            for (JsonNode block : results) {
                if (budget[0] <= 0) {
                    break;
                }
                budget[0]--;
                String line = blockToText(block);
                if (!line.isBlank()) {
                    out.append(line).append("\n");
                }
                if (block.path("has_children").asBoolean(false)) {
                    String childId = block.path("id").asText(null);
                    if (childId != null) {
                        collectBlocks(httpClient, baseUrl, childId, token, notionVersion,
                                timeoutSeconds, budget, depth + 1, out, errors);
                    }
                }
            }
            if (!response.path("has_more").asBoolean(false)) {
                break;
            }
            String next = response.path("next_cursor").asText(null);
            if (next == null || next.isBlank()) {
                break;
            }
            cursor = next;
        }
    }

    private JsonNode fetchBlockChildren(HttpClient httpClient, String baseUrl, String blockId, String cursor,
                                        String token, String notionVersion, int timeoutSeconds)
            throws IOException, InterruptedException {
        String url = baseUrl + "/v1/blocks/" + encode(blockId) + "/children?page_size=100";
        if (cursor != null && !cursor.isBlank()) {
            url += "&start_cursor=" + encode(cursor);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + token)
                .header("Notion-Version", notionVersion)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    // --- Notion block / rich-text -> plain text ------------------------------

    /** Flatten a single Notion block's {@code rich_text} array to plain text. */
    static String blockToText(JsonNode block) {
        String type = block.path("type").asText("");
        if (!RICH_TEXT_BLOCKS.contains(type)) {
            return "";
        }
        JsonNode richText = block.path(type).path("rich_text");
        String text = richTextToString(richText);
        if (type.equals("to_do")) {
            boolean checked = block.path(type).path("checked").asBoolean(false);
            return (checked ? "[x] " : "[ ] ") + text;
        }
        return text;
    }

    /** Concatenate the {@code plain_text} of a Notion rich-text span array. */
    static String richTextToString(JsonNode richText) {
        if (richText == null || !richText.isArray() || richText.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode span : richText) {
            sb.append(span.path("plain_text").asText(""));
        }
        return sb.toString();
    }

    /** Pull the page title from whichever property has type {@code title}. */
    static String extractTitle(JsonNode page) {
        JsonNode properties = page.path("properties");
        if (properties.isObject()) {
            for (JsonNode property : properties) {
                if ("title".equals(property.path("type").asText(""))) {
                    return richTextToString(property.path("title")).strip();
                }
            }
        }
        return "";
    }

    // --- helpers -------------------------------------------------------------

    private String resolveToken(NotionConfig config, ConnectorContext context) {
        if (config.tokenSecret() != null && !config.tokenSecret().isBlank()) {
            String resolved = context.resolveSecret(config.tokenSecret());
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return config.token();
    }

    private String normaliseBase(String uri) throws ConnectorException {
        if (uri == null || uri.isBlank()) {
            throw new ConnectorException("notion source uri (API base URL) is required");
        }
        String trimmed = uri.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI parsed = new URI(trimmed);
            String scheme = parsed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ConnectorException("notion base URL must be http(s): " + uri);
            }
        } catch (URISyntaxException e) {
            throw new ConnectorException("Invalid notion base URL: " + uri, e);
        }
        return trimmed;
    }

    private NotionConfig parseConfig(String rawJson) throws ConnectorException {
        if (rawJson == null || rawJson.isBlank()) {
            return NotionConfig.EMPTY;
        }
        try {
            return objectMapper.readValue(rawJson, NotionConfig.class);
        } catch (IOException e) {
            throw new ConnectorException("Invalid notion source config JSON: " + e.getMessage(), e);
        }
    }

    private String jsonString(String value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            // String serialization never fails; fall back to a naive quote.
            return "\"" + value.replace("\"", "\\\"") + "\"";
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
    record NotionConfig(
            String token,
            String tokenSecret,
            String query,
            String notionVersion,
            Integer pageSize,
            Integer maxPages,
            Integer maxBlocksPerPage,
            Integer timeoutSeconds) {

        static final NotionConfig EMPTY =
                new NotionConfig(null, null, null, null, null, null, null, null);
    }
}
