package ai.myrmec.engine.serviceaccount.dto;

import ai.myrmec.engine.serviceaccount.ServiceAccount;

import java.time.Instant;
import java.util.UUID;

/**
 * Admin-facing view of a {@link ServiceAccount}. Carries the full record —
 * including {@code keycloakClientId} — because this surface is restricted to
 * platform/org admins. No secret is ever stored or returned (Keycloak holds the
 * credential).
 */
public record ServiceAccountResponse(
        UUID id,
        UUID projectId,
        String name,
        String description,
        String keycloakClientId,
        boolean enabled,
        int rateLimitPerMin,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static ServiceAccountResponse of(ServiceAccount sa) {
        return new ServiceAccountResponse(
                sa.getId(),
                sa.getProjectId(),
                sa.getName(),
                sa.getDescription(),
                sa.getKeycloakClientId(),
                sa.isEnabled(),
                sa.getRateLimitPerMin(),
                sa.getCreatedBy(),
                sa.getCreatedAt(),
                sa.getUpdatedAt());
    }
}
