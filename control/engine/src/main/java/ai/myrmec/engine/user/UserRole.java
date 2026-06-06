package ai.myrmec.engine.user;

import ai.myrmec.engine._system.exception.BadRequestException;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * User role assignment. Roles can be scoped to the whole system, to a group, or
 * to a single project. A user may hold multiple roles simultaneously across and
 * within scopes (effective permissions = union, after implicit-role expansion).
 */
@Entity
@Table(name = "user_roles")
@Getter
@Setter
@NoArgsConstructor
public class UserRole {

    /**
     * Role catalog. Privilege model is intentionally not a single linear axis —
     * see {@link #isAtLeast(Role)} for the data-access subset and the resolver
     * for implicit-role expansion (PROJECT_OWNER → EDITOR → VIEWER, etc.).
     */
    public enum Role {
        /** Platform / technical admin: models, providers, agents, auth, global secrets, users. */
        PLATFORM_ADMIN,
        /** Business governance: groups, role grants, policy, audit. Implicit AUDITOR. */
        ORG_ADMIN,
        /** Project-scope owner: delete/transfer, manage members, set project quota. Implicit EDITOR. */
        PROJECT_OWNER,
        /** Authoring rights on workflows / secrets / knowledge repos. */
        EDITOR,
        /** Read-only data access in scope. */
        VIEWER,
        /** Quota-only role; no data access. */
        BUDGET_OWNER,
        /** HITL approver in scope. Implicit VIEWER. */
        APPROVER,
        /** Read-only across all data + audit + spend in scope. */
        AUDITOR;

        /**
         * Check if this role has at least the data-access privilege of {@code other}.
         *
         * <p>Only meaningful on the data-access axis (PROJECT_OWNER &gt; EDITOR &gt; VIEWER).
         * For roles off this axis (PLATFORM_ADMIN, ORG_ADMIN, BUDGET_OWNER, APPROVER,
         * AUDITOR) the result is {@code false}; consult the resolver for those.
         */
        public boolean isAtLeast(Role other) {
            int my = dataAccessRank();
            int otherRank = other.dataAccessRank();
            return my > 0 && otherRank > 0 && my >= otherRank;
        }

        public int dataAccessRank() {
            return switch (this) {
                case PROJECT_OWNER -> 3;
                case EDITOR -> 2;
                case VIEWER -> 1;
                default -> -1;
            };
        }

        /**
         * Roles implied by this one (transitive closure, including the role itself).
         *
         * <p>Used by the authz resolver to expand a granted role into the full
         * set of effective roles the holder can act under at that scope.
         *
         * <ul>
         *   <li>{@code PROJECT_OWNER} &rArr; {@code EDITOR} &rArr; {@code VIEWER}</li>
         *   <li>{@code EDITOR} &rArr; {@code VIEWER}</li>
         *   <li>{@code APPROVER} &rArr; {@code VIEWER}</li>
         *   <li>{@code AUDITOR} &rArr; {@code VIEWER} (auditor is read-only across data + audit + spend)</li>
         *   <li>{@code ORG_ADMIN} &rArr; {@code AUDITOR} &rArr; {@code VIEWER} (governance includes observability of data)</li>
         * </ul>
         *
         * <p>{@code PLATFORM_ADMIN} does <b>not</b> imply any data-access role:
         * platform admins manage infra and must be separately granted to read
         * project content (separation of duties).
         */
        public java.util.Set<Role> impliedRoles() {
            return switch (this) {
                case PROJECT_OWNER -> java.util.Set.of(PROJECT_OWNER, EDITOR, VIEWER);
                case EDITOR -> java.util.Set.of(EDITOR, VIEWER);
                case APPROVER -> java.util.Set.of(APPROVER, VIEWER);
                case AUDITOR -> java.util.Set.of(AUDITOR, VIEWER);
                case ORG_ADMIN -> java.util.Set.of(ORG_ADMIN, AUDITOR, VIEWER);
                default -> java.util.Set.of(this);
            };
        }
    }

    /**
     * Scope at which a role is granted.
     */
    public enum ScopeType {
        SYSTEM,
        GROUP,
        PROJECT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 10)
    private ScopeType scopeType;

    /** Set when {@code scopeType == GROUP}. */
    @Column(name = "group_id")
    private UUID groupId;

    /** Set when {@code scopeType == PROJECT}. */
    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Foreign key to users table. The user who granted this role. */
    @Column(name = "granted_by_user_id")
    private UUID grantedByUserId;

    @PrePersist
    protected void onCreate() {
        validateScope();
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        validateScope();
    }

    private void validateScope() {
        if (scopeType == null) {
            throw new BadRequestException("scopeType is required");
        }
        switch (scopeType) {
            case SYSTEM -> {
                if (groupId != null || projectId != null) {
                    throw new BadRequestException(
                            "SYSTEM-scoped role must not have groupId or projectId");
                }
            }
            case GROUP -> {
                if (groupId == null || projectId != null) {
                    throw new BadRequestException(
                            "GROUP-scoped role requires groupId and forbids projectId");
                }
            }
            case PROJECT -> {
                if (projectId == null || groupId != null) {
                    throw new BadRequestException(
                            "PROJECT-scoped role requires projectId and forbids groupId");
                }
            }
        }
    }

    /** Convenience: true when this is a SYSTEM-scoped row. */
    public boolean isSystemWide() {
        return scopeType == ScopeType.SYSTEM;
    }

    /**
     * Format role for JWT claim.
     * <ul>
     *   <li>SYSTEM:  {@code sys:ROLE}</li>
     *   <li>GROUP:   {@code grp:<groupId>:ROLE}</li>
     *   <li>PROJECT: {@code proj:<projectId>:ROLE}</li>
     * </ul>
     */
    public String toJwtClaim() {
        return switch (scopeType) {
            case SYSTEM -> "sys:" + role.name();
            case GROUP -> "grp:" + groupId + ":" + role.name();
            case PROJECT -> "proj:" + projectId + ":" + role.name();
        };
    }
}
