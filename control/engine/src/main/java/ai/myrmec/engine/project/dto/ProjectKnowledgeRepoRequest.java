package ai.myrmec.engine.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Request DTO for creating or updating a project knowledge repo entry.
 *
 * <p>{@code credentialSecretId} references a {@link ai.myrmec.engine.secret.Secret}
 * (global or project-scoped) of an authentication-capable type (BEARER_TOKEN,
 * USERNAME_PASSWORD, or SSL_PRIVATE_KEY). Null = unauthenticated clone.
 */
public record ProjectKnowledgeRepoRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 200, message = "Name cannot exceed 200 characters")
        String name,

        @NotBlank(message = "Repository URL is required")
        @Size(max = 500, message = "Repository URL cannot exceed 500 characters")
        String repoUrl,

        @Size(max = 100, message = "Branch cannot exceed 100 characters")
        String branch,

        List<String> instructionPaths,

        UUID credentialSecretId
) {}
