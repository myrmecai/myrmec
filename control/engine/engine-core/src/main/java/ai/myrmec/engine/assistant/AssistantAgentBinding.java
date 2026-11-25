// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.assistant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * AssistantAgentBinding — binds an agent profile (and optionally a specific
 * model) to an {@link AssistantVersion} for multi-model orchestration (design
 * §multi-model-role-bindings).
 *
 * <p>Each row assigns a role ({@link RoleType#PRIMARY} orchestrator or a
 * {@link RoleType#SUB_AGENT}) to an {@code agent_profiles} row plus an optional
 * {@code models.code} override. {@code agent_alias} is the logical router key
 * used by the orchestrator to invoke a sub-agent.
 *
 * <p>Keyed to {@code assistant_version_id} (not {@code assistant_id}) so a
 * published version's ensemble is immutable: models/profiles cannot change once
 * the version publishes. The concrete {@code agent_profile_versions} row is
 * resolved and frozen here at publish time ({@code agent_profile_version_id}),
 * so behaviour cannot drift mid-conversation even if the profile republishes a
 * newer version.</p>
 */
@Entity
@Table(name = "assistant_agent_bindings")
@Getter
@Setter
@NoArgsConstructor
public class AssistantAgentBinding {

    /** Role this binding plays within the assistant's multi-model ensemble. */
    public enum RoleType {
        /** The orchestrating/primary agent for the assistant. */
        PRIMARY,
        /** A subordinate agent the primary may delegate to. */
        SUB_AGENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "assistant_version_id", nullable = false)
    private UUID assistantVersionId;

    /** The profile this role uses (definition-time reference). */
    @Column(name = "agent_profile_id", nullable = false)
    private UUID agentProfileId;

    /** The frozen published profile version, resolved at publish time. */
    @Column(name = "agent_profile_version_id")
    private UUID agentProfileVersionId;

    /** Optional specific LLM engine; null falls back to the profile's default model. */
    @Column(name = "model_code", length = 50)
    private String modelCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, length = 40)
    private RoleType roleType;

    /** Logical identifier for router/sub-agent invocation (e.g. "coder"). */
    @Column(name = "agent_alias", nullable = false, length = 100)
    private String agentAlias;

    /** Optional localized prompt addendum (profile must opt-in via addendum_allowed). */
    @Column(name = "prompt_addendum", columnDefinition = "text")
    private String promptAddendum;
}
