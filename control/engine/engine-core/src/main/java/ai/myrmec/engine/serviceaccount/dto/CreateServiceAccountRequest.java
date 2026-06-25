package ai.myrmec.engine.serviceaccount.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request to provision a new {@link ai.myrmec.engine.serviceaccount.ServiceAccount}
 * (External API, #95).
 *
 * <p>{@code keycloakClientId} is the client/azp claim the account's
 * client-credentials tokens will carry — it maps a validated external token to
 * this row. {@code rateLimitPerMin} is optional; when omitted the platform
 * default (60/min) is used.</p>
 */
public record CreateServiceAccountRequest(
        @NotNull UUID projectId,
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description,
        @NotBlank @Size(max = 255) String keycloakClientId,
        @Min(1) Integer rateLimitPerMin
) {
}
