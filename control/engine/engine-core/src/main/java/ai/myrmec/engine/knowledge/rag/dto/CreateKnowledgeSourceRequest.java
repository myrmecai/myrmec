package ai.myrmec.engine.knowledge.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Add a connector-backed source to a knowledge base.
 *
 * @param connectorType registered connector id (e.g. {@code git}, {@code manual}).
 * @param name          display name, unique within the knowledge base.
 * @param uri           connector-specific location (e.g. a git remote URL).
 * @param configJson    optional connector configuration as a raw JSON string.
 * @param syncSchedule  optional cron expression; null/blank means manual-only.
 */
public record CreateKnowledgeSourceRequest(
        @NotBlank @Size(max = 50) String connectorType,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 2000) String uri,
        String configJson,
        @Size(max = 100) String syncSchedule
) {
}
