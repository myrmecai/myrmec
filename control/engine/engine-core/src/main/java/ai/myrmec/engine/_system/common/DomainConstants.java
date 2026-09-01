// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.common;

/**
 * Centralised domain constants shared across the engine, UI, and agents.
 *
 * <p>These constants replace hard-coded string literals that represent the
 * same conceptual value across multiple components. The UI mirrors these
 * in {@code ui/src/lib/domain-constants.ts} — the names and values must
 * stay in sync.</p>
 *
 * <h2>Categories</h2>
 * <ul>
 *   <li>{@link Scope} — entity scoping (org-wide vs project-scoped)</li>
 *   <li>{@link EntityStatus} — versioned-entity lifecycle status</li>
 *   <li>{@link ConnectionType} — connection config protocol types</li>
 *   <li>{@link TestStatus} — test-connection outcome</li>
 *   <li>{@link AuditAction} — audit event action types</li>
 *   <li>{@link Availability} — instruction asset / knowledge source availability</li>
 *   <li>{@link ActorType} — audit actor identity ("USER" vs "SYSTEM")</li>
 *   <li>{@link PrincipalType} — JWT principal claim ("AGENT" vs "USER")</li>
 * </ul>
 *
 * <p><strong>Already-enumerated concepts</strong> (ModelStatus, DeploymentType,
 * HealthStatus, CredentialType, UserRole.Role) are not duplicated here —
 * those enums are the single source of truth and should be used directly.</p>
 */
public final class DomainConstants {

    private DomainConstants() {}

    /**
     * Entity scope — determines visibility across the org/project hierarchy.
     *
     * <p>Used by: connection_configs, instruction_assets, knowledge_providers,
     * knowledge_sources, data_feeds. The {@code scope} column stores these
     * values as varchar(20).</p>
     *
     * <p><strong>Note:</strong> {@code UserRole.ScopeType} uses a different
     * vocabulary ({@code SYSTEM}/{@code GROUP}/{@code PROJECT}) for user-role
     * scoping — that is a separate concern and is not shared here.</p>
     */
    public static final class Scope {
        /** Organisation-wide scope (visible to all projects). */
        public static final String ORGANIZATION = "ORGANIZATION";
        /** Project-scoped (visible only within the owning project). */
        public static final String PROJECT = "PROJECT";

        private Scope() {}
    }

    /**
     * Versioned-entity lifecycle status — used by all entities that follow
     * the Zone-1/Zone-2 versioned pattern (connection configs, instruction
     * assets, knowledge providers).
     *
     * <p>State machine: {@code INCOMPLETE} → {@code DRAFT} →
     * {@code PUBLISHED} → {@code ACTIVE} → {@code DISABLED} →
     * {@code ARCHIVED} (with {@code REENABLED} returning to ACTIVE).</p>
     *
     * <p>Stored in the {@code status} column on the parent table and the
     * version table. Some values serve double duty as audit event types
     * (see {@link AuditAction}).</p>
     */
    public static final class EntityStatus {
        /** Parent has no published version yet (initial state after create). */
        public static final String INCOMPLETE = "INCOMPLETE";
        /** Version is being edited (Zone 2 draft). */
        public static final String DRAFT = "DRAFT";
        /** Version is the current live release. */
        public static final String PUBLISHED = "PUBLISHED";
        /** Parent entity is live and visible (has a published version). */
        public static final String ACTIVE = "ACTIVE";
        /** Parent entity is manually turned off (not visible in context). */
        public static final String DISABLED = "DISABLED";
        /** Parent entity is archived (hidden, read-only). */
        public static final String ARCHIVED = "ARCHIVED";

        private EntityStatus() {}
    }

    /**
     * Connection config protocol types — determines how the connection
     * is tested and how credentials are applied.
     *
     * <p>Stored in the {@code type} column on {@code connection_configs}.
     * The UI {@code ConnectionType} type union mirrors these values.</p>
     */
    public static final class ConnectionType {
        /** HTTP/REST API connection (requires testEndpoint). */
        public static final String HTTP = "HTTP";
        /** Git repository connection (tested via {@code git ls-remote}). */
        public static final String GIT = "GIT";
        /** S3-compatible object storage (tested via HEAD request). */
        public static final String S3 = "S3";
        /** Database connection (JDBC URL format validation). */
        public static final String DB = "DB";
        /** Managed RAG service (tested like HTTP). */
        public static final String MANAGED_RAG = "MANAGED_RAG";

        private ConnectionType() {}
    }

    /**
     * Test-connection outcome — returned by {@code testConnection} endpoints
     * and stored in {@code test_status} on connection_config_versions.
     *
     * <p><strong>Not</strong} the same as workflow task result status, which
     * uses lowercase values ({@code "success"}/{@code "failed"}).</p>
     */
    public static final class TestStatus {
        /** Connection test succeeded. */
        public static final String SUCCESS = "SUCCESS";
        /** Connection test failed (network error, non-2xx, etc.). */
        public static final String FAILED = "FAILED";

        private TestStatus() {}
    }

    /**
     * Audit event action types — the {@code eventType} parameter passed to
     * {@code AuditEventService.recordEvent(...)}.
     *
     * <p>These are <em>generic actions</em> — the entity type is recorded
     * separately in the {@code entity_type} column (see {@link ResourceType}).
     * So instead of {@code "MODEL_PROVIDER_CREATED"}, the code writes
     * {@code eventType=CREATED} + {@code entityType=model_provider}.</p>
     *
     * <p>Some values overlap with {@link EntityStatus} (PUBLISHED, DISABLED,
     * ARCHIVED) — they serve double duty as both entity status and audit
     * action. The non-overlapping ones are listed here.</p>
     */
    public static final class AuditAction {
        /** Entity was created. */
        public static final String CREATED = "CREATED";
        /** Entity was updated (Zone 1 edit). */
        public static final String UPDATED = "UPDATED";
        /** Entity was deleted. */
        public static final String DELETED = "DELETED";
        /** A new draft version was created. */
        public static final String DRAFT_CREATED = "DRAFT_CREATED";
        /** A draft version was discarded. */
        public static final String DRAFT_DISCARDED = "DRAFT_DISCARDED";
        // PUBLISHED, DISABLED, ARCHIVED — reuse EntityStatus values
        /** Entity was re-enabled (from DISABLED back to ACTIVE). */
        public static final String REENABLED = "REENABLED";
        /** Entity was un-archived (restored from ARCHIVED to ACTIVE/DISABLED). */
        public static final String UNARCHIVED = "UNARCHIVED";
        /** A version was cloned (from an archived version to a new draft). */
        public static final String VERSION_CLONED = "VERSION_CLONED";
        /** A connection/model test was performed. */
        public static final String TESTED = "TESTED";
        /** An attachment was uploaded. */
        public static final String UPLOADED = "UPLOADED";
        /** An attachment was quarantined (failed security scan). */
        public static final String QUARANTINED = "QUARANTINED";
        /** An attachment was bound to a message. */
        public static final String BOUND = "BOUND";
        /** A configuration value was changed. */
        public static final String CONFIG_CHANGED = "CONFIG_CHANGED";
        /** A setting value was changed. */
        public static final String SETTING_CHANGED = "SETTING_CHANGED";
        /** A governance profile was changed. */
        public static final String GOVERNANCE_PROFILE_CHANGED = "GOVERNANCE_PROFILE_CHANGED";
        /** A quota limit was changed. */
        public static final String QUOTA_LIMIT_CHANGED = "QUOTA_LIMIT_CHANGED";
        /** A quota was paused. */
        public static final String PAUSED = "PAUSED";
        /** A quota was resumed. */
        public static final String RESUMED = "RESUMED";
        /** An audit chain was activated. */
        public static final String AUDIT_CHAIN_ACTIVATED = "AUDIT_CHAIN_ACTIVATED";
        /** An audit chain was deactivated. */
        public static final String AUDIT_CHAIN_DEACTIVATED = "AUDIT_CHAIN_DEACTIVATED";
        /** A secret leak was detected in model output. */
        public static final String OUTPUT_SECRET_LEAK = "OUTPUT_SECRET_LEAK";
        /** Secret metadata was updated (without changing the secret value). */
        public static final String METADATA_UPDATED = "METADATA_UPDATED";
        /** User logged in successfully. */
        public static final String LOGIN = "LOGIN";
        /** User login failed. */
        public static final String LOGIN_FAILED = "LOGIN_FAILED";
        /** A role was granted to a user. */
        public static final String USER_ROLE_GRANTED = "USER_ROLE_GRANTED";

        private AuditAction() {}
    }

    /**
     * Instruction asset / knowledge source availability — determines whether
     * the asset is automatically included in the context or requires explicit
     * opt-in per project.
     *
     * <p>Stored in the {@code availability} column on
     * {@code instruction_asset_versions} and {@code knowledge_sources}.</p>
     */
    public static final class Availability {
        /** Always included in the context (no opt-in needed). */
        public static final String REQUIRED = "REQUIRED";
        /** Included only when explicitly enabled via a project binding. */
        public static final String OPTIONAL = "OPTIONAL";

        private Availability() {}
    }

    /**
     * Audit actor type — identifies whether the actor is a human user or
     * the system (for background/scheduled operations).
     *
     * <p>Used as the {@code actorType} / {@code actorDisplayName} parameter
     * to {@code AuditEventService.recordEvent(...)} when there is no
     * authenticated user (e.g. scheduled tasks, bootstrap).</p>
     */
    public static final class ActorType {
        /** The actor is the system (background task, bootstrap, etc.). */
        public static final String SYSTEM = "SYSTEM";
        /** The actor is a human user. */
        public static final String USER = "USER";

        private ActorType() {}
    }

    /**
     * JWT principal type — the {@code principal} claim in the JWT token.
     *
     * <p>Also used as a Spring role: {@code ROLE_AGENT} / {@code ROLE_USER}.</p>
     */
    public static final class PrincipalType {
        /** Machine agent principal (authenticates via registration key). */
        public static final String AGENT = "AGENT";
        /** Human user principal (authenticates via login). */
        public static final String USER = "USER";

        private PrincipalType() {}
    }
}