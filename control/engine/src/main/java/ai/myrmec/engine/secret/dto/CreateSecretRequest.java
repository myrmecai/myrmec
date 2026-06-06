package ai.myrmec.engine.secret.dto;

import ai.myrmec.engine.secret.CredentialType;
import ai.myrmec.engine.secret.SecretBackend;
import ai.myrmec.engine.secret.SecretPayload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create payload accepted by both the project-scoped and global secret endpoints.
 *
 * <p>{@code payload} is polymorphic — Jackson selects the right
 * {@link SecretPayload} subtype based on its {@code type} field, which must
 * match {@link #type}. Validation enforces that pairing in the service layer.
 *
 * <p>{@code backend} defaults to {@link SecretBackend#LOCAL}; the UI hides it
 * until external backends are wired.
 */
public record CreateSecretRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]*$",
                message = "Name must start with a letter and contain only letters, digits, dashes or underscores")
        String name,

        @NotNull(message = "Type is required")
        CredentialType type,

        SecretBackend backend,

        @NotNull(message = "Payload is required")
        SecretPayload payload
) {
}
