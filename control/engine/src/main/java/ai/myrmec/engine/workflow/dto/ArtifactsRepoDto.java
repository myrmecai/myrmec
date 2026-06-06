package ai.myrmec.engine.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * DTO for artifacts repository configuration.
 * 
 * <p>Used to configure git-based code generation workflows where
 * agents write code to a repository branch.
 */
public record ArtifactsRepoDto(
        /**
         * Git repository URL (HTTPS format).
         */
        @NotBlank @Size(max = 500) String url,
        
        /**
         * Base branch to create feature branch from (default: main).
         */
        @Size(max = 100) String baseBranch,
        
        /**
         * ID of a {@link ai.myrmec.engine.secret.Secret} (global or project-scoped)
         * holding git credentials — typically a BEARER_TOKEN or USERNAME_PASSWORD.
         * Null = unauthenticated.
         */
        UUID credentialSecretId
) {
    /**
     * Returns the base branch, defaulting to "main" if not specified.
     */
    public String getEffectiveBaseBranch() {
        return baseBranch != null && !baseBranch.isBlank() ? baseBranch : "main";
    }
}
