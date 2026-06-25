package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.spi.connector.ConnectorContext;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.EmittedChunk;
import ai.myrmec.engine.spi.connector.KnowledgeSourceConnector;
import ai.myrmec.engine.spi.connector.SourceLocator;
import ai.myrmec.engine.spi.connector.SyncResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link KnowledgeSourceConnector} (type = {@code "web-crawl"}) that performs a
 * breadth-first crawl of an HTTP(S) site, extracts readable text from each HTML
 * page, and emits one chunk per page (#23).
 *
 * <p>The source {@code uri} is the seed URL. The crawl follows in-page links up
 * to a configurable depth and page cap, staying on the seed's host by default
 * (or within an explicit allow-list). This grounds assistants on policy portals
 * and vendor docs that have no dedicated connector.</p>
 *
 * <p><strong>Config JSON</strong> (all optional) read from
 * {@code knowledge_sources.config_json}:</p>
 * <pre>{@code
 * {
 *   "maxDepth": 2,                      // link hops from the seed (default 1)
 *   "maxPages": 50,                     // hard page ceiling (default 50)
 *   "sameDomainOnly": true,             // stay on the seed host (default true)
 *   "allowedDomains": ["docs.acme.com"],// extra hosts to allow
 *   "includePathPrefix": "/handbook",   // only crawl/emit paths under here
 *   "userAgent": "MyrmecBot/1.0",       // sent as User-Agent
 *   "timeoutSeconds": 10                 // per-request timeout (default 10)
 * }
 * }</pre>
 *
 * <p>Each emitted chunk's locator is the page URL and the content is the
 * stripped, whitespace-collapsed visible text. A per-page fetch/parse failure
 * is recorded in {@link SyncResult#errors()} (downgrading to
 * {@link SyncResult.Status#PARTIAL}); a failure fetching the seed itself is
 * non-recoverable and throws {@link ConnectorException}.</p>
 *
 * <p>MVP scope: no JavaScript rendering and no {@code robots.txt} handling.
 * Operators should only point this at sites they are authorised to crawl.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WebCrawlConnector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "web-crawl";

    private static final int DEFAULT_MAX_DEPTH = 1;
    private static final int DEFAULT_MAX_PAGES = 50;
    private static final int DEFAULT_TIMEOUT_SECONDS = 10;
    private static final int HARD_PAGE_LIMIT = 1_000;

    /** Strip whole {@code <script>}/{@code <style>} blocks before tag removal. */
    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HREF = Pattern.compile("(?is)<a\\s+[^>]*?href\\s*=\\s*[\"']([^\"'#]+)[\"']");

    private final ObjectMapper objectMapper;

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        WebCrawlConfig config = parseConfig(locator.config().get("raw"));

        URI seed = parseSeed(locator.uri());
        int maxDepth = positiveOr(config.maxDepth(), DEFAULT_MAX_DEPTH);
        int maxPages = Math.min(positiveOr(config.maxPages(), DEFAULT_MAX_PAGES), HARD_PAGE_LIMIT);
        Set<String> allowedHosts = resolveAllowedHosts(seed, config);
        HttpClient httpClient = buildHttpClient(config);

        List<String> errors = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<CrawlTarget> queue = new ArrayDeque<>();
        queue.add(new CrawlTarget(seed, 0));
        visited.add(canonical(seed));

        long emitted = 0;
        boolean seedFetched = false;
        while (!queue.isEmpty() && emitted < maxPages) {
            CrawlTarget target = queue.poll();
            String html;
            try {
                html = fetch(httpClient, target.url(), config);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (!seedFetched) {
                    throw new ConnectorException(
                            "Failed to fetch seed URL " + target.url() + ": " + e.getMessage(), e);
                }
                errors.add("fetch failed: " + target.url() + " — " + e.getMessage());
                continue;
            }
            seedFetched = true;
            if (html == null) {
                continue; // non-HTML response — skipped silently
            }

            EmittedChunk chunk = toChunk(target, html);
            if (chunk != null) {
                context.chunkSink().accept(chunk);
                emitted++;
            }

            if (target.depth() < maxDepth) {
                enqueueLinks(target, html, allowedHosts, config, visited, queue);
            }
        }

        log.info("web-crawl emitted {} page chunk(s) from {} (depth<={}, {} error(s))",
                emitted, seed, maxDepth, errors.size());
        SyncResult.Status status = errors.isEmpty() ? SyncResult.Status.SUCCESS : SyncResult.Status.PARTIAL;
        Instant completedAt = Instant.now();
        return new SyncResult(status, emitted, errors, Duration.between(startedAt, completedAt), completedAt);
    }

    // --- fetch ---------------------------------------------------------------

    private HttpClient buildHttpClient(WebCrawlConfig config) {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(positiveOr(config.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS)))
                .build();
    }

    /** Returns the HTML body, or {@code null} when the response is not HTML. */
    private String fetch(HttpClient httpClient, URI url, WebCrawlConfig config)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(positiveOr(config.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS)))
                .header("User-Agent", config.userAgent() == null || config.userAgent().isBlank()
                        ? "MyrmecBot/1.0" : config.userAgent())
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode());
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.isBlank() && !contentType.toLowerCase(Locale.ROOT).contains("html")) {
            return null;
        }
        return response.body();
    }

    // --- transform -----------------------------------------------------------

    private EmittedChunk toChunk(CrawlTarget target, String html) {
        String text = htmlToText(html);
        if (text.isBlank()) {
            return null;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("url", target.url().toString());
        metadata.put("depth", Integer.toString(target.depth()));
        String title = extractTitle(html);
        if (title != null) {
            metadata.put("title", title);
        }
        return new EmittedChunk(target.url().toString(), text, metadata);
    }

    static String htmlToText(String html) {
        String withoutBlocks = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        String withoutTags = TAG.matcher(withoutBlocks).replaceAll(" ");
        String decoded = decodeEntities(withoutTags);
        return WHITESPACE.matcher(decoded).replaceAll(" ").strip();
    }

    private String extractTitle(String html) {
        Matcher m = TITLE.matcher(html);
        if (m.find()) {
            String title = WHITESPACE.matcher(decodeEntities(m.group(1))).replaceAll(" ").strip();
            return title.isBlank() ? null : title;
        }
        return null;
    }

    private static String decodeEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    // --- link discovery ------------------------------------------------------

    private void enqueueLinks(CrawlTarget parent, String html, Set<String> allowedHosts,
                              WebCrawlConfig config, Set<String> visited, Deque<CrawlTarget> queue) {
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            URI resolved = resolveLink(parent.url(), m.group(1));
            if (resolved == null || !isCrawlable(resolved, allowedHosts, config)) {
                continue;
            }
            if (visited.add(canonical(resolved))) {
                queue.add(new CrawlTarget(resolved, parent.depth() + 1));
            }
        }
    }

    private URI resolveLink(URI base, String href) {
        try {
            URI resolved = base.resolve(href.strip()).normalize();
            String scheme = resolved.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            return resolved;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private boolean isCrawlable(URI url, Set<String> allowedHosts, WebCrawlConfig config) {
        String host = url.getHost();
        if (host == null) {
            return false;
        }
        boolean sameDomainOnly = config.sameDomainOnly() == null || config.sameDomainOnly();
        if (sameDomainOnly && !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (config.includePathPrefix() != null && !config.includePathPrefix().isBlank()) {
            String path = url.getPath() == null ? "" : url.getPath();
            return path.startsWith(config.includePathPrefix());
        }
        return true;
    }

    // --- helpers -------------------------------------------------------------

    private Set<String> resolveAllowedHosts(URI seed, WebCrawlConfig config) {
        Set<String> hosts = new HashSet<>();
        if (seed.getHost() != null) {
            hosts.add(seed.getHost().toLowerCase(Locale.ROOT));
        }
        if (config.allowedDomains() != null) {
            for (String domain : config.allowedDomains()) {
                if (domain != null && !domain.isBlank()) {
                    hosts.add(domain.toLowerCase(Locale.ROOT));
                }
            }
        }
        return hosts;
    }

    /** Canonical visited-key: scheme://host/path?query (fragment already stripped). */
    private static String canonical(URI url) {
        String path = url.getPath() == null || url.getPath().isBlank() ? "/" : url.getPath();
        String query = url.getQuery() == null ? "" : "?" + url.getQuery();
        return (url.getScheme() + "://" + url.getAuthority() + path + query).toLowerCase(Locale.ROOT);
    }

    private URI parseSeed(String uri) throws ConnectorException {
        try {
            URI seed = new URI(uri.strip());
            String scheme = seed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new ConnectorException("web-crawl seed must be an http(s) URL: " + uri);
            }
            if (seed.getHost() == null) {
                throw new ConnectorException("web-crawl seed has no host: " + uri);
            }
            return seed;
        } catch (URISyntaxException e) {
            throw new ConnectorException("Invalid web-crawl seed URL: " + uri, e);
        }
    }

    private WebCrawlConfig parseConfig(String rawJson) throws ConnectorException {
        if (rawJson == null || rawJson.isBlank()) {
            return WebCrawlConfig.EMPTY;
        }
        try {
            return objectMapper.readValue(rawJson, WebCrawlConfig.class);
        } catch (IOException e) {
            throw new ConnectorException("Invalid web-crawl source config JSON: " + e.getMessage(), e);
        }
    }

    private static int positiveOr(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private record CrawlTarget(URI url, int depth) {
    }

    /**
     * Connector-owned config schema deserialised from
     * {@code knowledge_sources.config_json}. Unknown keys are ignored so the
     * schema can grow without breaking older sources.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WebCrawlConfig(
            Integer maxDepth,
            Integer maxPages,
            Boolean sameDomainOnly,
            List<String> allowedDomains,
            String includePathPrefix,
            String userAgent,
            Integer timeoutSeconds) {

        static final WebCrawlConfig EMPTY =
                new WebCrawlConfig(null, null, null, null, null, null, null);
    }
}
