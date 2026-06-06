package ai.myrmec.engine.secret.dto;

import ai.myrmec.engine.secret.CredentialType;
import ai.myrmec.engine.secret.Secret;
import ai.myrmec.engine.secret.SecretBackend;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata-only view of a {@link Secret}. The payload is never serialised back
 * to clients — consumers reference secrets by {@link #id} and the engine
 * resolves payloads internally.
 */
public record SecretResponse(
        UUID id,
        String name,
        CredentialType type,
        String scope,           // "GLOBAL" or "PROJECT"
        SecretBackend backend,
        UUID projectId,         // null for globals
        String projectName,     // null for globals
        UUID createdById,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt
) {

    public static SecretResponse from(Secret secret) {
        boolean global = secret.isGlobal();
        return new SecretResponse(
                secret.getId(),
                secret.getName(),
                secret.getType(),
                global ? "GLOBAL" : "PROJECT",
                secret.getBackend(),
                global ? null : secret.getProject().getId(),
                global ? null : secret.getProject().getName(),
                secret.getCreatedBy() != null ? secret.getCreatedBy().getId() : null,
                secret.getCreatedBy() != null ? secret.getCreatedBy().getEmail() : null,
                secret.getCreatedAt(),
                secret.getUpdatedAt()
        );
    }
}
