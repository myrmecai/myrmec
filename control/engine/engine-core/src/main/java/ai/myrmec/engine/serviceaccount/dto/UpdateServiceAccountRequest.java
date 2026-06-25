package ai.myrmec.engine.serviceaccount.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a {@link ai.myrmec.engine.serviceaccount.ServiceAccount}.
 * Every field is optional; a {@code null} leaves the corresponding column
 * unchanged. {@code enabled} is the kill switch; {@code rateLimitPerMin}
 * retunes the per-account limit.
 *
 * <p>{@code projectId} and {@code keycloakClientId} are immutable after
 * creation and therefore not updatable here.</p>
 */
public record UpdateServiceAccountRequest(
        @Size(max = 120) String name,
        @Size(max = 2000) String description,
        Boolean enabled,
        @Min(1) Integer rateLimitPerMin
) {
}
