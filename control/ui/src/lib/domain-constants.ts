// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

/**
 * Centralised domain constants shared across the UI, engine, and agents.
 *
 * These constants replace hard-coded string literals that represent the
 * same conceptual value across multiple components. The backend mirrors
 * these in `engine/_system/common/DomainConstants.java` — the names and
 * values must stay in sync.
 *
 * Already-typed concepts (ModelStatus, DeploymentType, HealthStatus,
 * CredentialType, SystemRole) are not duplicated here — those types are
 * the single source of truth and should be used directly.
 */

/** Entity scope — determines visibility across the org/project hierarchy. */
export const Scope = {
  /** Organisation-wide scope (visible to all projects). */
  ORGANIZATION: 'ORGANIZATION',
  /** Project-scoped (visible only within the owning project). */
  PROJECT: 'PROJECT',
} as const

export type Scope = (typeof Scope)[keyof typeof Scope]

/**
 * Versioned-entity lifecycle status — used by all entities that follow
 * the Zone-1/Zone-2 versioned pattern (connection configs, instruction
 * assets, knowledge providers).
 *
 * State machine: INCOMPLETE → DRAFT → PUBLISHED → ACTIVE → DISABLED →
 * ARCHIVED (with REENABLED returning to ACTIVE).
 */
export const EntityStatus = {
  /** Parent has no published version yet (initial state after create). */
  INCOMPLETE: 'INCOMPLETE',
  /** Version is being edited (Zone 2 draft). */
  DRAFT: 'DRAFT',
  /** Version is the current live release. */
  PUBLISHED: 'PUBLISHED',
  /** Parent entity is live and visible (has a published version). */
  ACTIVE: 'ACTIVE',
  /** Parent entity is manually turned off (not visible in context). */
  DISABLED: 'DISABLED',
  /** Parent entity is archived (hidden, read-only). */
  ARCHIVED: 'ARCHIVED',
} as const

export type EntityStatus = (typeof EntityStatus)[keyof typeof EntityStatus]

/** Connection config protocol types — determines how the connection is tested. */
export const ConnectionType = {
  /** HTTP/REST API connection (requires testEndpoint). */
  HTTP: 'HTTP',
  /** Git repository connection (tested via `git ls-remote`). */
  GIT: 'GIT',
  /** S3-compatible object storage (tested via HEAD request). */
  S3: 'S3',
  /** Database connection (JDBC URL format validation). */
  DB: 'DB',
  /** Managed RAG service (tested like HTTP). */
  MANAGED_RAG: 'MANAGED_RAG',
} as const

export type ConnectionType = (typeof ConnectionType)[keyof typeof ConnectionType]

/**
 * Test-connection outcome — returned by `testConnection` endpoints.
 *
 * NOT the same as workflow task result status, which uses lowercase
 * values (`"success"`/`"failed"`).
 */
export const TestStatus = {
  /** Connection test succeeded. */
  SUCCESS: 'SUCCESS',
  /** Connection test failed (network error, non-2xx, etc.). */
  FAILED: 'FAILED',
} as const

export type TestStatus = (typeof TestStatus)[keyof typeof TestStatus]

/** Audit event action types — the `eventType` passed to audit service. */
export const AuditAction = {
  /** Entity was created. */
  CREATED: 'CREATED',
  /** Entity was updated (Zone 1 edit). */
  UPDATED: 'UPDATED',
  /** Entity was deleted. */
  DELETED: 'DELETED',
  /** A new draft version was created. */
  DRAFT_CREATED: 'DRAFT_CREATED',
  /** A draft version was discarded. */
  DRAFT_DISCARDED: 'DRAFT_DISCARDED',
  /** Entity was re-enabled (from DISABLED back to ACTIVE). */
  REENABLED: 'REENABLED',
  /** Entity was un-archived (restored from ARCHIVED to ACTIVE/DISABLED). */
  UNARCHIVED: 'UNARCHIVED',
  /** A version was cloned (from an archived version to a new draft). */
  VERSION_CLONED: 'VERSION_CLONED',
  /** A connection/model test was performed. */
  TESTED: 'TESTED',
  /** An attachment was uploaded. */
  UPLOADED: 'UPLOADED',
  /** An attachment was quarantined (failed security scan). */
  QUARANTINED: 'QUARANTINED',
  /** An attachment was bound to a message. */
  BOUND: 'BOUND',
  /** A configuration value was changed. */
  CONFIG_CHANGED: 'CONFIG_CHANGED',
  /** A setting value was changed. */
  SETTING_CHANGED: 'SETTING_CHANGED',
  /** A governance profile was changed. */
  GOVERNANCE_PROFILE_CHANGED: 'GOVERNANCE_PROFILE_CHANGED',
  /** A quota limit was changed. */
  QUOTA_LIMIT_CHANGED: 'QUOTA_LIMIT_CHANGED',
  /** A quota was paused. */
  PAUSED: 'PAUSED',
  /** A quota was resumed. */
  RESUMED: 'RESUMED',
  /** An audit chain was activated. */
  AUDIT_CHAIN_ACTIVATED: 'AUDIT_CHAIN_ACTIVATED',
  /** An audit chain was deactivated. */
  AUDIT_CHAIN_DEACTIVATED: 'AUDIT_CHAIN_DEACTIVATED',
  /** A secret leak was detected in model output. */
  OUTPUT_SECRET_LEAK: 'OUTPUT_SECRET_LEAK',
  /** Secret metadata was updated (without changing the secret value). */
  METADATA_UPDATED: 'METADATA_UPDATED',
  /** User logged in successfully. */
  LOGIN: 'LOGIN',
  /** User login failed. */
  LOGIN_FAILED: 'LOGIN_FAILED',
  /** A role was granted to a user. */
  USER_ROLE_GRANTED: 'USER_ROLE_GRANTED',
} as const

export type AuditAction = (typeof AuditAction)[keyof typeof AuditAction]

/**
 * Instruction asset / knowledge source availability — determines whether
 * the asset is automatically included in the context or requires explicit
 * opt-in per project.
 */
export const Availability = {
  /** Always included in the context (no opt-in needed). */
  REQUIRED: 'REQUIRED',
  /** Included only when explicitly enabled via a project binding. */
  OPTIONAL: 'OPTIONAL',
} as const

export type Availability = (typeof Availability)[keyof typeof Availability]

/** Audit actor type — identifies whether the actor is a human user or the system. */
export const ActorType = {
  /** The actor is the system (background task, bootstrap, etc.). */
  SYSTEM: 'SYSTEM',
  /** The actor is a human user. */
  USER: 'USER',
} as const

export type ActorType = (typeof ActorType)[keyof typeof ActorType]

/** JWT principal type — the `principal` claim in the JWT token. */
export const PrincipalType = {
  /** Machine agent principal (authenticates via registration key). */
  AGENT: 'AGENT',
  /** Human user principal (authenticates via login). */
  USER: 'USER',
} as const

export type PrincipalType = (typeof PrincipalType)[keyof typeof PrincipalType]

/**
 * Resource type identifiers — the `entity_type` column in `audit_events`.
 * Identifies what kind of entity an audit event is about.
 *
 * All values use `snake_case` for consistency. Mirrors `ResourceType.java`.
 */
export const ResourceType = {
  /** Connection config (UC-018 versioned entity). */
  CONNECTION_CONFIG: 'connection_config',
  /** Instruction asset (UC-KM-02 versioned entity). */
  INSTRUCTION_ASSET: 'instruction_asset',
  /** Knowledge provider (UC-KM-03 versioned entity). */
  KNOWLEDGE_PROVIDER: 'knowledge_provider',
  /** Knowledge source (flat entity within a provider version). */
  KNOWLEDGE_SOURCE: 'knowledge_source',
  /** Data feed (ingestion pipeline config). */
  DATA_FEED: 'data_feed',
  /** Model provider (LLM provider configuration). */
  MODEL_PROVIDER: 'model_provider',
  /** Model (LLM model configuration within a provider). */
  MODEL: 'model',
  /** Secret (vault credential). */
  SECRET: 'secret',
  /** Project (organisational unit for agents and conversations). */
  PROJECT: 'project',
  /** Agent (registered machine agent). */
  AGENT: 'agent',
  /** Conversation (chat session within a project). */
  CONVERSATION: 'conversation',
  /** Conversation message attachment (file upload). */
  CONVERSATION_ATTACHMENT: 'conversation_attachment',
  /** Governance profile (STRICT / STANDARD / FLEXIBLE). */
  GOVERNANCE_PROFILE: 'governance_profile',
  /** System setting (platform-level configuration). */
  SYSTEM_SETTING: 'system_setting',
  /** Project setting (project-level configuration). */
  PROJECT_SETTING: 'project_setting',
  /** Quota (budget / spending limit). */
  QUOTA: 'quota',
  /** User (human operator). */
  USER: 'user',
  /** Audit chain (hash-chain integrity activation). */
  AUDIT_CHAIN: 'audit_chain',
} as const

export type ResourceType = (typeof ResourceType)[keyof typeof ResourceType]

/**
 * Audit reason codes — the `reason_code` column in `audit_events`.
 * Explains why an action was taken, orthogonal to what action was taken.
 *
 * Most audit events have a null reason code. Mirrors `AuditReason.java`.
 */
export const AuditReason = {
  /** Entity was disabled by an admin via the lifecycle UI. */
  ADMIN_DISABLED: 'ADMIN_DISABLED',
  /** Entity was re-enabled by an admin via the lifecycle UI. */
  ADMIN_REENABLED: 'ADMIN_REENABLED',
  /** Entity was archived by an admin via the lifecycle UI. */
  ADMIN_ARCHIVED: 'ADMIN_ARCHIVED',
  /** Entity was un-archived by an admin (restored from ARCHIVED). */
  ADMIN_UNARCHIVED: 'ADMIN_UNARCHIVED',
  /** Project configuration was changed (e.g. agent profile, model). */
  CONFIG_CHANGED: 'CONFIG_CHANGED',
  /** A system or project setting value was changed. */
  SETTING_CHANGED: 'SETTING_CHANGED',
} as const

export type AuditReason = (typeof AuditReason)[keyof typeof AuditReason]