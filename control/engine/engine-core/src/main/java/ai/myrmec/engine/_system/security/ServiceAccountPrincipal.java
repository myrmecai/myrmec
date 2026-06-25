package ai.myrmec.engine._system.security;

import java.security.Principal;
import java.util.UUID;

/**
 * Authenticated principal for an External-API ({@code /api/v1/external/**})
 * caller — a machine {@link ai.myrmec.engine.serviceaccount.ServiceAccount}
 * resolved from a validated Keycloak client-credentials token.
 *
 * <p>Unlike {@link ai.myrmec.engine.user.UserPrincipal} it carries no role
 * expansion: a service account's authority is its single owning
 * {@link #projectId} plus the per-assistant {@code USE} grants checked at the
 * controller. {@link #getName()} returns the service-account id for audit.</p>
 */
public final class ServiceAccountPrincipal implements Principal {

    private final UUID serviceAccountId;
    private final UUID projectId;
    private final String keycloakClientId;
    private final String displayName;

    public ServiceAccountPrincipal(UUID serviceAccountId, UUID projectId,
                                   String keycloakClientId, String displayName) {
        this.serviceAccountId = serviceAccountId;
        this.projectId = projectId;
        this.keycloakClientId = keycloakClientId;
        this.displayName = displayName;
    }

    public UUID getServiceAccountId() {
        return serviceAccountId;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getKeycloakClientId() {
        return keycloakClientId;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Stable identifier for audit/logging — the service-account id. */
    @Override
    public String getName() {
        return serviceAccountId.toString();
    }
}
