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
import java.util.Set;

/**
 * {@link KnowledgeSourceConnector} (type = {@code "jira"}) that ingests issues
 * from an Atlassian Jira Cloud project (#24 — second SaaS connector).
 *
 * <p>Jira Cloud rides the same Atlassian platform as Confluence, so this
 * connector mirrors {@link ConfluenceConnector}: HTTP Basic auth with
 * {@code email:apiToken}, offset pagination, one chunk per record. The one
 * material difference is the body format — Jira issue descriptions and comments
 * are <strong>ADF</strong> (Atlassian Document Format, a JSON tree) rather than
 * Confluence storage HTML — so this connector ships {@link #adfToText(JsonNode)}
 * instead of reusing {@link WebCrawlConnector#htmlToText(String)}.</p>
 *
 * <p>The source {@code uri} is the Jira base URL (e.g.
 * {@code https://acme.atlassian.net}). Issues are pulled from the Cloud REST API
 * ({@code GET {base}/rest/api/3/search?jql=…&startAt=N&maxResults=M}) and each
 * issue becomes one chunk.</p>
 *
 * <p><strong>Config JSON</strong> read from {@code knowledge_sources.config_json}:</p>
 * <pre>{@code
 * {
 *   "projectKey": "ENG",               // ingest one project (ignored when jql set)
 *   "jql": "project = ENG AND ...",    // explicit JQL; overrides projectKey
 *   "email": "bot@acme.com",           // Basic-auth user (Atlassian account email)
 *   "apiTokenSecret": "jira-token",    // secret ref resolved via ConnectorContext
 *   "apiToken": "…",                   // inline token fallback (dev/test)
 *   "includeComments": true,           // append issue comments to the chunk body
 *   "pageSize": 50,                    // results per API page (default 50, max 100)
 *   "maxIssues": 2000,                 // hard ceiling on issues ingested (default 2000)
 *   "timeoutSeconds": 15               // per-request timeout (default 15)
 * }
 * }</pre>
 *
 * <p>Either {@code projectKey} or {@code jql} is required. A per-issue parse
 * failure is recorded in {@link SyncResult#errors()} (PARTIAL); a failure on the
 * first search page (auth/JQL error) is non-recoverable and throws
 * {@link ConnectorException}.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JiraConnector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "jira";

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_MAX_ISSUES = 2_000;
    private static final int HARD_ISSUE_LIMIT = 100_000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;

    /** ADF node types that map to a block boundary (trailing newline) in plain text. */
    private static final Set<String> ADF_BLOCK_TYPES = Set.of(
            "paragraph", "heading", "blockquote", "listItem", "codeBlock", "rule", "panel", "tableRow");

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        JiraConfig config = parseConfig(locator.config().get("raw"));
        String baseUrl = normaliseBase(locator.uri());
        String jql = resolveJql(config);

        String authHeader = buildAuthHeader(config, context);
        boolean includeComments = config.includeComments() == null || config.includeComments();
        int pageSize = clamp(config.pageSize(), DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
        int maxIssues = Math.min(positiveOr(config.maxIssues(), DEFAULT_MAX_ISSUES), HARD_ISSUE_LIMIT);
        int timeoutSeconds = positiveOr(config.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS);
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        List<String> errors = new ArrayList<>();
        long emitted = 0;
        int startAt = 0;
        boolean firstPage = true;

        while (emitted < maxIssues) {
            JsonNode response;
            try {
                response = fetchSearchPage(httpClient, baseUrl, jql, startAt, pageSize,
                        includeComments, authHeader, timeoutSeconds);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (firstPage) {
                    throw new ConnectorException(
                            "Failed to search Jira issues (jql='" + jql + "'): " + e.getMessage(), e);
                }
                errors.add("issue fetch failed at startAt=" + startAt + ": " + e.getMessage());
                break;
            }
            firstPage = false;

            JsonNode issues = response.path("issues");
            if (!issues.isArray() || issues.isEmpty()) {
                break;
            }
            for (JsonNode issue : issues) {
                if (emitted >= maxIssues) {
                    break;
                }
                EmittedChunk chunk = toChunk(baseUrl, issue, includeComments, errors);
                if (chunk != null) {
                    context.chunkSink().accept(chunk);
                    emitted++;
                }
            }
            int returned = issues.size();
            int total = response.path("total").asInt(Integer.MAX_VALUE);
            startAt += returned;
            if (returned < pageSize || startAt >= total) {
                break; // last page
            }
        }

        log.info("jira sync emitted {} issue chunk(s) for jql='{}' ({} error(s))",
                emitted, jql, errors.size());
        SyncResult.Status status = errors.isEmpty() ? SyncResult.Status.SUCCESS : SyncResult.Status.PARTIAL;
        Instant completedAt = Instant.now();
        return new SyncResult(status, emitted, errors, Duration.between(startedAt, completedAt), completedAt);
    }

    // --- fetch / transform ---------------------------------------------------

    private JsonNode fetchSearchPage(HttpClient httpClient, String baseUrl, String jql, int startAt,
                                     int maxResults, boolean includeComments, String authHeader,
                                     int timeoutSeconds) throws IOException, InterruptedException {
        String fields = includeComments
                ? "summary,description,status,issuetype,project,comment"
                : "summary,description,status,issuetype,project";
        String url = baseUrl + "/rest/api/3/search"
                + "?jql=" + encode(jql)
                + "&startAt=" + startAt
                + "&maxResults=" + maxResults
                + "&fields=" + encode(fields);
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

    private EmittedChunk toChunk(String baseUrl, JsonNode issue, boolean includeComments, List<String> errors) {
        try {
            String key = issue.path("key").asText(null);
            if (key == null) {
                return null;
            }
            JsonNode fields = issue.path("fields");
            String summary = fields.path("summary").asText("");
            String description = adfToText(fields.path("description"));

            StringBuilder body = new StringBuilder();
            if (!summary.isBlank()) {
                body.append(summary).append("\n\n");
            }
            if (!description.isBlank()) {
                body.append(description);
            }
            if (includeComments) {
                String comments = extractComments(fields.path("comment").path("comments"));
                if (!comments.isBlank()) {
                    if (body.length() > 0) {
                        body.append("\n\n");
                    }
                    body.append(comments);
                }
            }
            String text = body.toString().strip();
            if (text.isBlank()) {
                return null;
            }

            String projectKey = fields.path("project").path("key").asText(projectPrefix(key));
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("projectKey", projectKey);
            metadata.put("issueKey", key);
            String status = fields.path("status").path("name").asText(null);
            if (status != null && !status.isBlank()) {
                metadata.put("status", status);
            }
            String issueType = fields.path("issuetype").path("name").asText(null);
            if (issueType != null && !issueType.isBlank()) {
                metadata.put("issueType", issueType);
            }
            metadata.put("url", baseUrl + "/browse/" + key);

            return new EmittedChunk(key, text, metadata);
        } catch (RuntimeException e) {
            errors.add("issue parse error: " + e.getMessage());
            return null;
        }
    }

    private String extractComments(JsonNode comments) {
        if (!comments.isArray() || comments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode comment : comments) {
            String author = comment.path("author").path("displayName").asText("");
            String text = adfToText(comment.path("body"));
            if (text.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            if (!author.isBlank()) {
                sb.append(author).append(": ");
            }
            sb.append(text);
        }
        return sb.toString();
    }

    // --- ADF (Atlassian Document Format) -> plain text -----------------------

    /**
     * Flatten an Atlassian Document Format node tree into plain text. Recurses
     * {@code content[]}, emits {@code text}/{@code mention} leaf content, turns
     * {@code hardBreak} into a newline, and appends a newline after block-level
     * nodes. Package-visible so future Atlassian connectors can reuse it.
     */
    static String adfToText(JsonNode doc) {
        if (doc == null || doc.isNull() || doc.isMissingNode()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendAdf(doc, sb);
        return sb.toString().strip().replaceAll("\n{3,}", "\n\n");
    }

    private static void appendAdf(JsonNode node, StringBuilder sb) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        String type = node.path("type").asText("");
        switch (type) {
            case "text" -> {
                sb.append(node.path("text").asText(""));
                return;
            }
            case "hardBreak" -> {
                sb.append("\n");
                return;
            }
            case "mention" -> {
                sb.append(node.path("attrs").path("text").asText(""));
                return;
            }
            case "emoji" -> {
                sb.append(node.path("attrs").path("text").asText(""));
                return;
            }
            default -> {
                // fall through to recurse children
            }
        }
        JsonNode content = node.path("content");
        if (content.isArray()) {
            for (JsonNode child : content) {
                appendAdf(child, sb);
            }
        }
        if (ADF_BLOCK_TYPES.contains(type)) {
            sb.append("\n");
        }
    }

    // --- helpers -------------------------------------------------------------

    private String resolveJql(JiraConfig config) throws ConnectorException {
        if (config.jql() != null && !config.jql().isBlank()) {
            return config.jql().strip();
        }
        if (config.projectKey() != null && !config.projectKey().isBlank()) {
            return "project = \"" + config.projectKey().strip() + "\" ORDER BY created ASC";
        }
        throw new ConnectorException("jira source requires either 'jql' or 'projectKey' in config");
    }

    private String buildAuthHeader(JiraConfig config, ConnectorContext context) throws ConnectorException {
        String token = resolveToken(config, context);
        if (token == null || token.isBlank()) {
            throw new ConnectorException("jira source requires an API token (apiTokenSecret or apiToken)");
        }
        String email = config.email() == null ? "" : config.email();
        String credentials = email + ":" + token;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String resolveToken(JiraConfig config, ConnectorContext context) {
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
            throw new ConnectorException("jira source uri (base URL) is required");
        }
        String trimmed = uri.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        try {
            URI parsed = new URI(trimmed);
            String scheme = parsed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ConnectorException("jira base URL must be http(s): " + uri);
            }
        } catch (URISyntaxException e) {
            throw new ConnectorException("Invalid jira base URL: " + uri, e);
        }
        return trimmed;
    }

    private JiraConfig parseConfig(String rawJson) throws ConnectorException {
        if (rawJson == null || rawJson.isBlank()) {
            return JiraConfig.EMPTY;
        }
        try {
            return objectMapper.readValue(rawJson, JiraConfig.class);
        } catch (IOException e) {
            throw new ConnectorException("Invalid jira source config JSON: " + e.getMessage(), e);
        }
    }

    private static String projectPrefix(String issueKey) {
        int dash = issueKey.indexOf('-');
        return dash > 0 ? issueKey.substring(0, dash) : issueKey;
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
    record JiraConfig(
            String projectKey,
            String jql,
            String email,
            String apiToken,
            String apiTokenSecret,
            Boolean includeComments,
            Integer pageSize,
            Integer maxIssues,
            Integer timeoutSeconds) {

        static final JiraConfig EMPTY =
                new JiraConfig(null, null, null, null, null, null, null, null, null);
    }
}
