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
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link KnowledgeSourceConnector} (type = {@code "s3"}) that ingests documents
 * from an S3-protocol object store (#22).
 *
 * <p>Because the S3 API is a de-facto standard, a single MinIO-backed client
 * ({@link MinioObjectStoreFactory}) reaches AWS S3, Cloudflare R2, Backblaze B2,
 * DigitalOcean Spaces, MinIO, and GCS's S3-interop endpoint — no per-provider
 * SDK. (Azure Blob, which has no S3 interop, would be a separate connector.)</p>
 *
 * <p>The source {@code uri} is {@code s3://<bucket>/<prefix>} — the prefix is
 * optional and selects which keys to ingest. Credentials are resolved via the
 * {@link ConnectorContext} secret resolver ({@code accessKeySecret} /
 * {@code secretKeySecret}) with inline fallbacks; public buckets need neither.</p>
 *
 * <p><strong>Config JSON</strong> (all optional) read from
 * {@code knowledge_sources.config_json}:</p>
 * <pre>{@code
 * {
 *   "endpoint": "https://s3.eu-west-1.amazonaws.com", // overrides region default
 *   "region": "eu-west-1",
 *   "accessKeySecret": "kb-bucket-access-key",
 *   "secretKeySecret": "kb-bucket-secret-key",
 *   "accessKey": "AKIA…",                              // inline fallback (dev)
 *   "secretKey": "…",
 *   "pathStyle": true,                                  // MinIO / most S3-compatible
 *   "includeGlobs": ["handbook/**"],                    // if set, only matching keys
 *   "excludeGlobs": ["**\/drafts/**"],                  // always skipped
 *   "includeExtensions": ["pdf", "md"],                 // restrict supported types
 *   "maxFileSizeBytes": 10485760                         // skip larger (default 10 MiB)
 * }
 * }</pre>
 *
 * <p>Text is extracted per {@link TextExtractor} (txt/md/html/pdf/docx); keys of
 * other types are skipped without downloading. One chunk per object: locator =
 * object key, metadata {@code {bucket, key, ext, size}}. A per-object
 * read/extract failure is recorded in {@link SyncResult#errors()} (PARTIAL); a
 * listing/auth failure is non-recoverable and throws {@link ConnectorException}.</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class S3Connector implements KnowledgeSourceConnector {

    public static final String CONNECTOR_TYPE = "s3";

    /** Default per-object size ceiling (10 MiB) when not overridden in config. */
    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 10_485_760L;

    private final ObjectMapper objectMapper;
    private final ObjectStoreFactory objectStoreFactory;

    @Override
    public String type() {
        return CONNECTOR_TYPE;
    }

    @Override
    public SyncResult sync(SourceLocator locator, ConnectorContext context) throws ConnectorException {
        Instant startedAt = Instant.now();
        S3SourceConfig config = parseConfig(locator.config().get("raw"));
        BucketAndPrefix target = parseS3Uri(locator.uri());

        ObjectStoreFactory.Settings settings = new ObjectStoreFactory.Settings(
                resolveEndpoint(config),
                config.region(),
                resolveSecret(config.accessKeySecret(), config.accessKey(), context),
                resolveSecret(config.secretKeySecret(), config.secretKey(), context),
                config.pathStyle() != null && config.pathStyle());

        long maxFileSize = config.maxFileSizeBytes() != null && config.maxFileSizeBytes() > 0
                ? config.maxFileSizeBytes()
                : DEFAULT_MAX_FILE_SIZE_BYTES;
        List<PathMatcher> includes = compileGlobs(config.includeGlobs());
        List<PathMatcher> excludes = compileGlobs(config.excludeGlobs());
        Set<String> allowedExtensions = lowerSet(config.includeExtensions());

        List<String> errors = new ArrayList<>();
        long emitted = 0;
        try (ObjectStore store = objectStoreFactory.create(settings)) {
            List<ObjectStore.Entry> objects = store.list(target.bucket(), target.prefix());
            for (ObjectStore.Entry object : objects) {
                if (!shouldIngest(object, includes, excludes, allowedExtensions, maxFileSize, errors)) {
                    continue;
                }
                EmittedChunk chunk = readChunk(store, target.bucket(), object, errors);
                if (chunk != null) {
                    context.chunkSink().accept(chunk);
                    emitted++;
                }
            }
            log.info("s3 sync emitted {} chunk(s) from s3://{}/{} ({} error(s))",
                    emitted, target.bucket(), target.prefix(), errors.size());
        } catch (IOException e) {
            throw new ConnectorException(
                    "Object-storage sync failed for s3://" + target.bucket() + "/" + target.prefix()
                            + ": " + e.getMessage(), e);
        }

        SyncResult.Status status = errors.isEmpty() ? SyncResult.Status.SUCCESS : SyncResult.Status.PARTIAL;
        Instant completedAt = Instant.now();
        return new SyncResult(status, emitted, errors, Duration.between(startedAt, completedAt), completedAt);
    }

    // --- per-object ----------------------------------------------------------

    private boolean shouldIngest(ObjectStore.Entry object, List<PathMatcher> includes,
                                 List<PathMatcher> excludes, Set<String> allowedExtensions,
                                 long maxFileSize, List<String> errors) {
        String key = object.key();
        if (key == null || key.endsWith("/")) {
            return false; // pseudo-directory placeholder
        }
        if (!TextExtractor.isSupported(key)) {
            return false; // unsupported binary — skip without downloading
        }
        if (!allowedExtensions.isEmpty() && !allowedExtensions.contains(TextExtractor.extension(key))) {
            return false;
        }
        if (!matchesFilters(key, includes, excludes)) {
            return false;
        }
        if (object.size() > maxFileSize) {
            errors.add("skipped (too large, " + object.size() + " bytes): " + key);
            return false;
        }
        return true;
    }

    private EmittedChunk readChunk(ObjectStore store, String bucket, ObjectStore.Entry object,
                                   List<String> errors) {
        String key = object.key();
        try {
            byte[] bytes = store.read(bucket, key);
            String text = TextExtractor.extract(key, bytes);
            if (text == null || text.isBlank()) {
                return null;
            }
            return new EmittedChunk(key, text, chunkMetadata(bucket, key, object.size()));
        } catch (IOException e) {
            errors.add("skipped (read/extract error): " + key + " — " + e.getMessage());
            return null;
        }
    }

    private Map<String, String> chunkMetadata(String bucket, String key, long size) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("bucket", bucket);
        metadata.put("key", key);
        String ext = TextExtractor.extension(key);
        if (!ext.isEmpty()) {
            metadata.put("ext", ext);
        }
        metadata.put("size", Long.toString(size));
        return metadata;
    }

    // --- helpers -------------------------------------------------------------

    private S3SourceConfig parseConfig(String rawJson) throws ConnectorException {
        if (rawJson == null || rawJson.isBlank()) {
            return S3SourceConfig.EMPTY;
        }
        try {
            return objectMapper.readValue(rawJson, S3SourceConfig.class);
        } catch (IOException e) {
            throw new ConnectorException("Invalid s3 source config JSON: " + e.getMessage(), e);
        }
    }

    private String resolveEndpoint(S3SourceConfig config) {
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            return config.endpoint();
        }
        if (config.region() != null && !config.region().isBlank()) {
            return "https://s3." + config.region() + ".amazonaws.com";
        }
        return "https://s3.amazonaws.com";
    }

    private String resolveSecret(String secretRef, String inlineValue, ConnectorContext context) {
        if (secretRef != null && !secretRef.isBlank()) {
            String resolved = context.resolveSecret(secretRef);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return inlineValue != null && !inlineValue.isBlank() ? inlineValue : null;
    }

    private BucketAndPrefix parseS3Uri(String uri) throws ConnectorException {
        try {
            URI parsed = new URI(uri.strip());
            String scheme = parsed.getScheme();
            if (scheme == null || !scheme.equalsIgnoreCase("s3")) {
                throw new ConnectorException("s3 source uri must start with s3:// — got: " + uri);
            }
            String bucket = parsed.getHost() != null ? parsed.getHost() : parsed.getAuthority();
            if (bucket == null || bucket.isBlank()) {
                throw new ConnectorException("s3 source uri has no bucket: " + uri);
            }
            String path = parsed.getPath() == null ? "" : parsed.getPath();
            String prefix = path.startsWith("/") ? path.substring(1) : path;
            return new BucketAndPrefix(bucket, prefix);
        } catch (URISyntaxException e) {
            throw new ConnectorException("Invalid s3 source uri: " + uri, e);
        }
    }

    private static boolean matchesFilters(String key, List<PathMatcher> includes, List<PathMatcher> excludes) {
        Path keyPath = Path.of(key);
        for (PathMatcher exclude : excludes) {
            if (exclude.matches(keyPath)) {
                return false;
            }
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (PathMatcher include : includes) {
            if (include.matches(keyPath)) {
                return true;
            }
        }
        return false;
    }

    private static List<PathMatcher> compileGlobs(List<String> globs) {
        if (globs == null || globs.isEmpty()) {
            return List.of();
        }
        List<PathMatcher> matchers = new ArrayList<>(globs.size());
        for (String glob : globs) {
            if (glob == null || glob.isBlank()) {
                continue;
            }
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            // Java's "**" does not match zero directories, so "**/*.md" misses a
            // top-level key. Add a root-level equivalent for the intuitive reading.
            if (glob.startsWith("**/")) {
                String rootEquivalent = glob.substring(3);
                if (!rootEquivalent.isBlank()) {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + rootEquivalent));
                }
            }
        }
        return matchers;
    }

    private static Set<String> lowerSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        List<String> lowered = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                lowered.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(lowered);
    }

    private record BucketAndPrefix(String bucket, String prefix) {
    }

    /**
     * Connector-owned config schema deserialised from
     * {@code knowledge_sources.config_json}. Unknown keys are ignored so the
     * schema can grow without breaking older sources.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record S3SourceConfig(
            String endpoint,
            String region,
            String accessKey,
            String secretKey,
            String accessKeySecret,
            String secretKeySecret,
            Boolean pathStyle,
            List<String> includeGlobs,
            List<String> excludeGlobs,
            List<String> includeExtensions,
            Long maxFileSizeBytes) {

        static final S3SourceConfig EMPTY =
                new S3SourceConfig(null, null, null, null, null, null, null, null, null, null, null);
    }
}
