// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.common.JsonListConverter;
import ai.myrmec.engine.tool.Tool;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Agent Profile Version (design §16.1) — the immutable Zone 2 behaviour
 * contract of an {@link AgentProfile}, created through the Draft → Publish
 * cycle. Identity and administrative fields live on the parent; everything
 * the runtime executes against (system prompt, capabilities, tool set,
 * default model, interaction mode, orchestration policy) lives here.
 *
 * <p>Lifecycle: DRAFT (mutable) → PUBLISHED (immutable) → ARCHIVED (when
 * a newer version publishes). Published content never changes in place;
 * editing requires a new draft → publish cycle. Exactly one PUBLISHED
 * version exists per profile at a time (enforced by the service under the
 * parent row lock and by the partial unique index in the schema).
 *
 * <p>Orchestration policy fields (command templates with risk classes,
 * approval policy, required isolation, Git policy, workspace retention) are
 * nullable/inert for conversation profiles until orchestration consumes
 * them. Profile-level budget ceilings are deliberately absent: spend
 * governance belongs to the engine's quota tiers, not the profile.
 */
@Entity
@Table(name = "agent_profile_versions")
@Getter
@Setter
@NoArgsConstructor
public class AgentProfileVersion {

    public enum Status {
        DRAFT, PUBLISHED, ARCHIVED
    }

    /**
     * Required isolation level the Host must satisfy to run this profile
     * (design §7 ExecutionPolicy). NULL means the default
     * TRUSTED_PROCESS — the profile places no sandbox demand.
     */
    public enum Isolation {
        TRUSTED_PROCESS, UNTRUSTED_REPOSITORY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    /** Monotonic per-profile version number; 1 is the first publish. */
    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.DRAFT;

    /**
     * Tools assigned to this version via the junction table keyed to the
     * version (the tool set is part of the behaviour contract).
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "agent_profile_tools",
        joinColumns = @JoinColumn(name = "profile_version_id"),
        inverseJoinColumns = @JoinColumn(name = "tool_code")
    )
    private Set<Tool> tools = new HashSet<>();

    /**
     * Runtime requirements as JSON array.
     * Examples: ["python:>=3.11", "docker", "gpu:nvidia", "16gb-ram"]
     */
    @Convert(converter = JsonListConverter.class)
    @Column(name = "capabilities", nullable = false)
    private List<String> capabilities;

    /**
     * Default model code for agents running this version.
     */
    @Column(name = "default_model", length = 50)
    private String defaultModel;

    /**
     * System prompt for agents running this version.
     */
    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    /**
     * Whether agents running this version participate in conversational
     * sessions (Phase 6) or execute one-shot workflows (legacy path).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_mode", nullable = false, length = 20)
    private InteractionMode interactionMode = InteractionMode.ONE_SHOT;

    /**
     * Command templates as a nested JSON map (design §7): template name →
     * { executable, args, parameters, cwdPattern, environmentAllowlist,
     *   timeoutSeconds, maxOutputBytes, network, maxCpuSeconds,
     *   maxMemoryBytes, riskClass }. NULL until orchestration consumes it.
     */
    @Column(name = "command_templates", columnDefinition = "text")
    private String commandTemplates;

    /**
     * Approval policy as a JSON map keyed by `tool:<name>`,
     * `template:<name>`, and `action:<CHECKPOINT|PUSH>` with values
     * ALLOW/DENY/REQUIRE_APPROVAL (design §17.4). NULL = defaults.
     */
    @Column(name = "approval_policy", columnDefinition = "text")
    private String approvalPolicy;

    /**
     * Required isolation capability (design §7). NULL = TRUSTED_PROCESS.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "required_isolation", length = 30)
    private Isolation requiredIsolation;

    /**
     * Git policy as JSON: { allowCheckpoint, allowPush } (design §7).
     * NULL until orchestration consumes it.
     */
    @Column(name = "git_policy", columnDefinition = "text")
    private String gitPolicy;

    /**
     * Workspace retention window in seconds (design §7).
     */
    @Column(name = "workspace_retention_seconds")
    private Integer workspaceRetentionSeconds;

    /**
     * Approval-request TTL in seconds (design §17.4): the bounded window
     * an orchestration approval stays decidable before the sweeper applies
     * the terminal APPROVAL_EXPIRED tuple. NULL = platform default.
     */
    @Column(name = "approval_request_ttl_seconds")
    private Integer approvalRequestTtlSeconds;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    /**
     * The conversation participation mode (Phase 6). Mirrors the parent
     * profile's pre-F0 field; moved onto the version as Zone 2 behaviour.
     */
    public enum InteractionMode {
        ONE_SHOT, CONVERSATIONAL
    }
}