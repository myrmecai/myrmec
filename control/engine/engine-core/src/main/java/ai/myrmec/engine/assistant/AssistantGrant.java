package ai.myrmec.engine.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * AssistantGrant — one ACL entry on an {@link Assistant} (see
 * {@code docs/design/assistant-entity.md} §6). Grants are independent of
 * project membership and never bump a version.
 *
 * <p>Uses a surrogate {@code id} PK per the project's operational-data
 * convention, with a unique constraint on the natural key
 * {@code (assistant_id, principal_type, principal_id, permission)}.</p>
 */
@Entity
@Table(name = "assistant_grants")
@Getter
@Setter
@NoArgsConstructor
public class AssistantGrant {

    public enum PrincipalType { USER, ROLE, SERVICE_ACCOUNT }

    public enum Permission { OWNER, EDITOR, VIEWER, USE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "assistant_id", nullable = false)
    private UUID assistantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 20)
    private PrincipalType principalType;

    /** UUID string for USER / SERVICE_ACCOUNT; Keycloak role name for ROLE. */
    @Column(name = "principal_id", nullable = false, length = 255)
    private String principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 20)
    private Permission permission;

    @Column(name = "granted_at", nullable = false, updatable = false)
    private Instant grantedAt;

    @Column(name = "granted_by")
    private UUID grantedBy;

    @PrePersist
    void onCreate() {
        grantedAt = Instant.now();
    }
}
