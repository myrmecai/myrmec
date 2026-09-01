package ai.myrmec.engine.spi.retrieval;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable retrieval request handed to a {@link RetrievalProvider}.
 *
 * <p>Engine builds this from the agent's {@code ctx.retrieve()} call and
 * resolves {@code knowledgeSourceId} from the calling project's access scope
 * before dispatch. Providers MUST treat the value as read-only.</p>
 *
 * @param knowledgeSourceId non-null knowledge source the query targets; resolution and ACL
 *                          enforcement happen before the SPI is invoked.
 * @param query             non-blank natural-language query string. Providers
 *                          may rewrite or expand for embedding lookup but the
 *                          original is what the agent receives in citations.
 * @param topK              maximum number of hits to return. Providers MAY
 *                          return fewer (no padding required).
 * @param filters           opaque key/value filter map forwarded to the provider
 *                          (e.g. tag matches, file glob, date range). Never null;
 *                          use an empty map to indicate no filters.
 */
public record RetrievalQuery(
    @NotNull UUID knowledgeSourceId,
    @NotBlank String query,
    @Min(1) int topK,
    @NotNull Map<String, String> filters
) {
    public RetrievalQuery {
        filters = filters == null ? Map.of() : Collections.unmodifiableMap(filters);
    }
}
