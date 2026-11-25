// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._protocol.payload;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import ai.myrmec.engine._protocol.SessionCredential;

/**
 * Payload for the {@code session.open} frame — the unified V2 shape
 * (mirrors {@code agent-sdk/src/protocol/sessionOpenV2.ts} field-for-field).
 *
 * <p>One kind-agnostic payload serves both {@code CONVERSATION} and
 * {@code WORKFLOW} sessions. The wire fields are:</p>
 * <ul>
 *   <li>{@code profile} — who the agent is and what it can do (models map,
 *       primary model, tools, sub-agents, skills, knowledge sources).</li>
 *   <li>{@code workspace} — where the agent works ({@code none}/{@code git}/{@code local}).</li>
 *   <li>{@code policy} — budgets, command templates, isolation, capture, hold.</li>
 *   <li>{@code task} — the WORKFLOW step's goal/definition-of-done/checkpoint.</li>
 * </ul>
 *
 * <p>The SDK parses this with a <em>strict</em> Zod object: unknown fields are
 * rejected and required fields must be present. The engine must therefore emit
 * exactly this shape — no legacy V1 fields ({@code teamId} replaced the old
 * {@code projectId}; the single flat {@code model} became {@code profile.models}
 * + {@code profile.primaryModel}; tool {@code riskClass} became {@code approval}
 * + {@code allowedCommands}).</p>
 *
 * <p>All records use {@code NON_NULL} inclusion so that fields irrelevant to a
 * given discriminated-union branch (e.g. {@code sourceBaseCommit} on a
 * {@code git} workspace, or {@code source} on a {@code local} workspace) are
 * omitted rather than rejected as unknown keys by the SDK's strict Zod schema.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionOpenPayload(
        UUID sessionId,
        String kind,             // "CONVERSATION" | "WORKFLOW"
        UUID teamId,             // the Team (was "projectId" pre-rename)
        String ref,              // conversation id or workflow request id (nullable)
        Map<String, String> labels,  // opaque tracing labels (nullable)
        UUID resumeOf,           // previous sessionId this continues (nullable)
        AgentProfile profile,
        Workspace workspace,     // discriminated: none | git | local (never null on the wire)
        Policy policy,           // never null on the wire
        Task task,               // workflow-only; null for CONVERSATION
        ChannelOffer channel,    // dedicated-transport offer; null when disabled
        List<SessionCredential> credentials) {  // sealed secrets; empty when keyless

    // ------------------------------------------------------------------
    // 1. Agent Profile
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgentProfile(
            UUID versionId,              // pinned agent_profile_versions.id
            String code,                 // optional profile code
            String systemPrompt,         // nullable
            String primaryModel,         // key into models
            Map<String, ModelSpec> models,
            List<ToolGrant> tools,
            List<SubagentSpec> subagents,
            List<SkillRef> skills,
            List<KnowledgeSourceHandle> knowledgeSources,
            ContextManagement contextManagement) {  // nullable

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record ContextManagement(SummarizationPolicy summarization) {}  // summarization nullable
    }

    public record ModelSpec(
            String provider,
            String modelId,
            String description,   // nullable (.nullish on SDK — absent or null OK)
            String endpoint,      // nullable (.nullable on SDK — must be present, null OK)
            String credentialRef, // nullable (.nullable on SDK — must be present, null OK)
            Map<String, Object> parameters) {}  // never null (defaults to {})

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolGrant(
            String name,
            String approval,            // "NONE" | "REQUIRED"
            List<String> allowedCommands) {}  // names of pinned command templates

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubagentSpec(
            String name,
            String description,
            String systemPrompt,   // nullable
            String modelCode,      // key into profile.models
            List<ToolGrant> tools,
            Integer maxIterations) {}  // nullable

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SkillRef(
            String name,
            String description,
            SkillSource source) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record SkillSource(
                String kind,             // "inline" | "uri"
                Map<String, String> files,  // inline: path -> UTF-8 content (must include SKILL.md)
                String uri,              // uri kind
                String sha256) {}        // uri kind, nullable
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SummarizationPolicy(
            Trigger trigger,
            Trigger keep,
            String modelCode,     // nullable; key into profile.models
            String summaryPrompt) {  // nullable

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Trigger(String type, double value) {}  // type: messages|tokens|fraction
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record KnowledgeSourceHandle(
            UUID id,
            String name,
            String description) {}

    // ------------------------------------------------------------------
    // 2. Workspace (discriminated union: none | git | local)
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Workspace(
            String kind,             // "none" | "git" | "local"
            GitSource source,        // git kind only
            String sourceBaseCommit, // local kind only, nullable
            String targetBranch,     // local kind only, nullable
            String subPath) {        // nullable

        public record GitSource(
                String repoUrl,
                String sourceBranch,
                String sourceBaseCommit,
                String targetBranch,
                String credentialRef) {}  // nullable — must be present as null on the wire
    }

    // ------------------------------------------------------------------
    // 3. Policy
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Policy(
            Budget budget,                 // nullable
            Map<String, CommandTemplate> commandTemplates,
            String requiredIsolation,      // "TRUSTED_PROCESS" | "UNTRUSTED_REPOSITORY" | null
            CaptureSpec capture,           // nullable
            HoldPolicy interaction) {      // nullable

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Budget(
                Integer maxTokens,
                Integer maxModelCalls,
                Integer maxSubagentCalls,
                Integer maxIterations,
                Integer executionTimeoutSeconds,
                String onExceeded) {}     // "FAIL" | "PAUSE_FOR_HUMAN_REVIEW"

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record CommandTemplate(
                String executable,
                List<Object> args,         // each: String | { parameter: String }
                Map<String, Parameter> parameters,
                String cwdPattern,
                List<String> environmentAllowlist,
                Integer timeoutSeconds,
                Integer maxOutputBytes,
                String network,            // "DENY" | "ALLOW"
                Integer maxCpuSeconds,
                Integer maxMemoryBytes) {

            @JsonInclude(JsonInclude.Include.NON_NULL)
            public record Parameter(
                    String type,           // "string" | "integer" | "boolean"
                    boolean required,
                    String pattern,
                    List<Object> allowedValues) {}
        }

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record CaptureSpec(String level, Integer maxBytes) {}  // level: OFF|METADATA|FULL

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record HoldPolicy(
                boolean allowHold,
                int maxHoldSeconds,
                String onHoldTimeout) {}   // "RESUME" | "FAIL"
    }

    // ------------------------------------------------------------------
    // 4. Task (workflow-only)
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Task(
            String goal,
            String definitionOfDone,
            String specPath,   // nullable
            CheckpointStrategy checkpoint) {

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record CheckpointStrategy(
                String mode,           // "ON_VERIFICATION_PASS"
                String commitMessage,
                boolean pushToRemote,
                boolean requireApprovalBeforePush,
                boolean allowNoChanges) {}
    }

    // ------------------------------------------------------------------
    // 5. Transport
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ChannelOffer(String endpoint, String token) {}
}