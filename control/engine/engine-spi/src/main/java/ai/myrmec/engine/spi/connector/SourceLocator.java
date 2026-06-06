package ai.myrmec.engine.spi.connector;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.Map;

/**
 * Resolved location of a knowledge source.
 *
 * <p>Tells a {@link KnowledgeSourceConnector} <strong>what</strong> to fetch.
 * Format of {@link #uri()} is connector-specific:</p>
 * <ul>
 *   <li>git: {@code https://github.com/acme/api.git#main}</li>
 *   <li>object storage: {@code s3://my-bucket/prefix/}</li>
 *   <li>web crawl: {@code https://docs.example.com/} (with depth in config)</li>
 *   <li>database: {@code jdbc:postgresql://host:5432/db?schemas=public}</li>
 * </ul>
 *
 * @param uri       non-blank source URI in the connector's native format.
 * @param config    non-null connector-specific configuration map (depth,
 *                  prefixes, glob filters, ...). Engine populates this from
 *                  {@code knowledge_sources.config_json}. Use an empty map
 *                  instead of null for "no config".
 */
public record SourceLocator(
    @NotBlank String uri,
    @NotNull Map<String, String> config
) {
    public SourceLocator {
        config = config == null ? Map.of() : Collections.unmodifiableMap(config);
    }
}
