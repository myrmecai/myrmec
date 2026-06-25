package ai.myrmec.engine.knowledge.rag.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create a PROJECT-scoped knowledge base.
 *
 * @param name        display name, unique within the project.
 * @param description optional free-text description.
 * @param providerId  retrieval-provider id (e.g. {@code stub}); defaults to
 *                    {@code stub} when blank.
 * @param classification optional data-classification label.
 */
public record CreateKnowledgeBaseRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 4000) String description,
        @Size(max = 100) String providerId,
        @Size(max = 40) String classification
) {
}
