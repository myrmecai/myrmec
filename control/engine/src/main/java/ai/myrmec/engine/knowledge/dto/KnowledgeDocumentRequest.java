package ai.myrmec.engine.knowledge.dto;

import ai.myrmec.engine.knowledge.KnowledgeCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request DTO for creating or updating a knowledge document.
 */
public record KnowledgeDocumentRequest(
        @NotNull(message = "Category is required")
        KnowledgeCategory category,

        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name must not exceed 200 characters")
        String name,

        @NotBlank(message = "Content is required")
        String content,

        Integer priority,

        List<String> appliesTo,

        String sourcePath
) {
    /**
     * Constructor with defaults.
     */
    public KnowledgeDocumentRequest {
        if (priority == null) {
            priority = 100;
        }
    }
}
