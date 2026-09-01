// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine._system.common.DomainConstants.Availability;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine.assistant.AssistantContextBinding;
import ai.myrmec.engine.assistant.AssistantContextBindingRepository;
import ai.myrmec.engine.governance.EffectivePolicy;
import ai.myrmec.engine.governance.GovernancePolicyResolver;
import ai.myrmec.engine.governance.GovernanceScope;
import ai.myrmec.engine.governance.ProductFeature;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetRepository;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.instruction.InstructionAssetVersionRepository;
import ai.myrmec.engine.knowledge.KnowledgeProvider;
import ai.myrmec.engine.knowledge.KnowledgeProviderRepository;
import ai.myrmec.engine.knowledge.KnowledgeProviderVersion;
import ai.myrmec.engine.knowledge.KnowledgeProviderVersionRepository;
import ai.myrmec.engine.knowledge.KnowledgeSource;
import ai.myrmec.engine.knowledge.KnowledgeSourceRepository;
import ai.myrmec.engine.project.ProjectInstructionBinding;
import ai.myrmec.engine.project.ProjectInstructionBindingRepository;
import ai.myrmec.engine.project.ProjectProviderBinding;
import ai.myrmec.engine.project.ProjectProviderBindingRepository;
import ai.myrmec.engine.project.ProjectSettingRepository;
import ai.myrmec.engine.setting.SystemSettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ContextBuilder — the runtime component that assembles AI context at execution time.
 *
 * <p>This service is called before each turn (conversation or workflow) to build
 * the complete instruction set and knowledge source references that the agent
 * will use. It enforces governance policies, applies project and assistant-level
 * bindings, sorts by priority, estimates tokens, applies budget truncation, and
 * writes a {@link ContextManifest} audit record.</p>
 *
 * <p>See {@code ai-context-management.md} §3-§5 for the full design.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextBuilder {

    private static final String GOVERNANCE_PROFILE_KEY = "governance_profile_code";
    private static final String DEFAULT_GOVERNANCE_PROFILE = "STANDARD";
    private static final String CONTEXT_TOKEN_BUDGET_KEY = "context_token_budget";
    private static final int DEFAULT_TOKEN_BUDGET = 4000;
    private static final int CHARS_PER_TOKEN = 4; // rough estimate

    private final GovernancePolicyResolver governancePolicyResolver;
    private final SystemSettingService systemSettingService;
    private final InstructionAssetRepository instructionAssetRepository;
    private final InstructionAssetVersionRepository instructionAssetVersionRepository;
    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgeProviderRepository knowledgeProviderRepository;
    private final KnowledgeProviderVersionRepository knowledgeProviderVersionRepository;
    private final ProjectInstructionBindingRepository projectInstructionBindingRepository;
    private final ProjectProviderBindingRepository projectProviderBindingRepository;
    private final AssistantContextBindingRepository assistantContextBindingRepository;
    private final ProjectSettingRepository projectSettingRepository;
    private final ContextManifestRepository contextManifestRepository;

    /**
     * Assemble the AI context for a given project and optional assistant version.
     *
     * @param projectId         the project id (required)
     * @param assistantVersionId the assistant version id (null for workflow tasks)
     * @param serviceType        the service type for applicability filtering (e.g., "CONVERSATION", "WORKFLOW")
     * @param conversationId     the conversation id (for manifest writing, null for workflows)
     * @param messageId          the triggering message id (for manifest, null if N/A)
     * @param sequenceNo         the sequence number (for manifest, 0 if N/A)
     * @return the assembled context
     */
    @Transactional
    public AssembledContext assemble(
            UUID projectId,
            UUID assistantVersionId,
            String serviceType,
            UUID conversationId,
            UUID messageId,
            long sequenceNo) {
        return assemble(projectId, assistantVersionId, serviceType,
                conversationId, messageId, sequenceNo, null);
    }

    /**
     * Assemble the AI context with an optional execution context map.
     *
     * @param executionContext optional map of execution-context keys (e.g., "fileType":"SQL")
     *                         used for activation-rule evaluation. Pass null or empty map
     *                         when no file-type context is available (e.g., conversations).
     * @return the assembled context
     */
    @Transactional
    public AssembledContext assemble(
            UUID projectId,
            UUID assistantVersionId,
            String serviceType,
            UUID conversationId,
            UUID messageId,
            long sequenceNo,
            Map<String, Object> executionContext) {
        return assemble(projectId, assistantVersionId, serviceType,
                conversationId, messageId, sequenceNo, executionContext, null);
    }

    /**
     * Assemble the AI context with an optional execution context map and
     * an optional pinned {@link ContextSnapshot}.
     *
     * <p>When a snapshot is provided and the governance profile resolves to
     * {@code PINNED_AT_START}, instruction asset versions are loaded from the
     * snapshot instead of being resolved live. This ensures all turns of a
     * conversation see the same instruction versions, even if an asset is
     * republished mid-session.</p>
     *
     * @param executionContext optional map of execution-context keys
     * @param snapshot          optional pinned context snapshot (null for IMMEDIATE_EFFECT)
     * @return the assembled context
     */
    @Transactional
    public AssembledContext assemble(
            UUID projectId,
            UUID assistantVersionId,
            String serviceType,
            UUID conversationId,
            UUID messageId,
            long sequenceNo,
            Map<String, Object> executionContext,
            ContextSnapshot snapshot) {

        // 1. Resolve governance profile
        EffectivePolicy policy = governancePolicyResolver.resolve(GovernanceScope.ofProject(projectId));
        String profileCode = policy.code();
        String contextPinning = ProductFeature.CONTEXT_PINNING.runtimeName(policy.single(ProductFeature.CONTEXT_PINNING));

        // If a snapshot is provided, force PINNED_AT_START regardless of the current profile.
        // The snapshot was captured at conversation creation when the profile may have been
        // different; the pinned versions take precedence over live resolution.
        if (snapshot != null) {
            contextPinning = "PINNED_AT_START";
        }

        // 2. Resolve token budget from project settings
        int budgetTokens = getProjectIntSetting(projectId, CONTEXT_TOKEN_BUDGET_KEY, DEFAULT_TOKEN_BUDGET);

        // 3. Collect instruction entries
        List<AssembledContext.InstructionEntry> allInstructions = new ArrayList<>();
        List<Map<String, Object>> excludedInstructions = new ArrayList<>();

        if (snapshot != null) {
            // PINNED_AT_START: load instruction entries from the snapshot
            allInstructions = loadPinnedInstructions(snapshot.instructionAssetVersionIds(), excludedInstructions);
        } else {
            // IMMEDIATE_EFFECT or no snapshot: live resolution
            // 3a. Compute project bindings and assistant exclusions BEFORE collecting
            Set<UUID> enabledByProject = getEnabledInstructionBindings(projectId);
            Set<UUID> excludedByAssistant = getExcludedByAssistant(assistantVersionId, "INSTRUCTION_ASSET");

            // 3b. Org-scoped published instruction assets (with availability/binding enforcement)
            collectInstructions(Scope.ORGANIZATION, null, serviceType, allInstructions,
                    excludedInstructions, enabledByProject, excludedByAssistant,
                    executionContext);

            // 3c. Project-scoped published instruction assets (no binding check needed)
            collectInstructions(Scope.PROJECT, projectId, serviceType, allInstructions,
                    excludedInstructions, Set.of(), excludedByAssistant,
                    executionContext);
        }

        // 4. Sort by priority ascending (lowest first, highest last/closest to user message).
        //    Within identical priority, sort alphabetically by name for deterministic ordering.
        allInstructions.sort(Comparator
                .comparingInt(AssembledContext.InstructionEntry::priority)
                .thenComparing(AssembledContext.InstructionEntry::name));

        // 5. Estimate token counts and apply budget — two-pass with REQUIRED protection.
        //    Pass 1: compute REQUIRED tokens. If REQUIRED alone exceeds budget, set overflow.
        //    Pass 2: add REQUIRED first, then OPTIONAL in priority order until budget is met.
        //    OPTIONAL entries that don't fit are truncated. REQUIRED entries are never truncated.
        int requiredTokens = 0;
        for (AssembledContext.InstructionEntry entry : allInstructions) {
            if (Availability.REQUIRED.equals(entry.availability())) {
                requiredTokens += entry.tokenCount();
            }
        }

        boolean contextOverflow = requiredTokens > budgetTokens;
        int totalTokens = 0;
        List<AssembledContext.InstructionEntry> budgetCapped = new ArrayList<>();
        List<Map<String, Object>> truncatedInstructions = new ArrayList<>();
        boolean truncated = false;

        if (contextOverflow) {
            // REQUIRED assets alone exceed budget — keep all REQUIRED, truncate all OPTIONAL.
            // The overflow flag is set so callers can handle it (e.g. reject the turn).
            for (AssembledContext.InstructionEntry entry : allInstructions) {
                if (Availability.REQUIRED.equals(entry.availability())) {
                    budgetCapped.add(entry);
                    totalTokens += entry.tokenCount();
                } else {
                    truncated = true;
                    truncatedInstructions.add(Map.of(
                            "assetId", entry.assetId().toString(),
                            "name", entry.name(),
                            "reason", "CONTEXT_OVERFLOW"));
                }
            }
        } else {
            // Normal budget: add entries in priority order. OPTIONAL entries that
            // don't fit are truncated. REQUIRED entries are always kept.
            for (AssembledContext.InstructionEntry entry : allInstructions) {
                int entryTokens = entry.tokenCount();
                boolean isRequired = Availability.REQUIRED.equals(entry.availability());
                if (!isRequired && totalTokens + entryTokens > budgetTokens) {
                    truncated = true;
                    truncatedInstructions.add(Map.of(
                            "assetId", entry.assetId().toString(),
                            "name", entry.name(),
                            "reason", "BUDGET"));
                    continue;
                }
                budgetCapped.add(entry);
                totalTokens += entryTokens;
            }
        }

        // 6. Resolve knowledge sources
        List<AssembledContext.KnowledgeSourceRef> knowledgeSources = resolveKnowledgeSources(
                projectId, assistantVersionId);

        // 7. Write context manifest
        ContextManifest manifest = null;
        if (conversationId != null) {
            manifest = writeManifest(
                    conversationId, messageId, sequenceNo,
                    profileCode, contextPinning,
                    totalTokens, budgetTokens, truncated,
                    budgetCapped, excludedInstructions, truncatedInstructions,
                    knowledgeSources);
        }

        log.info("Assembled context for project {}: {} instructions ({} tokens / {} budget), {} knowledge sources, truncated={}",
                projectId, budgetCapped.size(), totalTokens, budgetTokens, knowledgeSources.size(), truncated);

        return new AssembledContext(
                budgetCapped,
                knowledgeSources,
                profileCode,
                contextPinning,
                totalTokens,
                budgetTokens,
                truncated,
                contextOverflow,
                manifest);
    }

    /**
     * Collect published instruction entries for a given scope.
     * Enforces availability (REQUIRED always included; OPTIONAL only if enabled by project binding),
     * assistant exclusions, applicability filtering, and activation rules during collection.
     *
     * @param executionContext optional map containing "fileType" etc. for activation-rule evaluation
     */
    private void collectInstructions(
            String scope, UUID projectId, String serviceType,
            List<AssembledContext.InstructionEntry> entries,
            List<Map<String, Object>> excluded,
            Set<UUID> enabledByProject,
            Set<UUID> excludedByAssistant,
            Map<String, Object> executionContext) {

        List<InstructionAsset> assets = projectId == null
                ? instructionAssetRepository.findByScopeAndProjectIdIsNull(scope)
                : instructionAssetRepository.findByScopeAndProjectId(scope, projectId);

        for (InstructionAsset asset : assets) {
            // --- Status filtering with proper exclusion reasons ---
            String status = asset.getStatus();
            if (!EntityStatus.ACTIVE.equals(status)) {
                // Map status to the standard taxonomy reason
                String reason;
                if (EntityStatus.INCOMPLETE.equals(status)) {
                    reason = EntityStatus.INCOMPLETE;
                } else if (EntityStatus.DISABLED.equals(status)) {
                    reason = EntityStatus.DISABLED;
                } else if (EntityStatus.ARCHIVED.equals(status)) {
                    reason = EntityStatus.ARCHIVED;
                } else {
                    reason = EntityStatus.DISABLED; // fallback for unexpected statuses
                }
                excluded.add(Map.of("assetId", asset.getId().toString(),
                        "name", asset.getName(), "reason", reason));
                continue;
            }

            // ACTIVE but no published version → INCOMPLETE
            InstructionAssetVersion version = instructionAssetVersionRepository
                    .findByAssetIdAndStatus(asset.getId(), EntityStatus.PUBLISHED)
                    .orElse(null);
            if (version == null) {
                excluded.add(Map.of("assetId", asset.getId().toString(),
                        "name", asset.getName(), "reason", EntityStatus.INCOMPLETE));
                continue;
            }

            // --- Applicability filtering (simplified) ---
            if (version.getApplicability() != null && !version.getApplicability().isEmpty()) {
                boolean matches = version.getApplicability().keySet().stream()
                        .anyMatch(k -> k.equalsIgnoreCase(serviceType));
                if (!matches) {
                    excluded.add(Map.of("assetId", asset.getId().toString(),
                            "name", asset.getName(), "reason", "NOT_APPLICABLE"));
                    continue;
                }
            }

            // --- Activation rules (v1: file-type only) ---
            if (version.getActivationRules() != null && !version.getActivationRules().isEmpty()) {
                Object ruleFileType = version.getActivationRules().get("fileType");
                if (ruleFileType instanceof String requiredFileType) {
                    String contextFileType = executionContext != null
                            ? (String) executionContext.get("fileType")
                            : null;
                    if (contextFileType == null || !contextFileType.equalsIgnoreCase(requiredFileType)) {
                        excluded.add(Map.of("assetId", asset.getId().toString(),
                                "name", asset.getName(), "reason", "ACTIVATION_NO_MATCH"));
                        continue;
                    }
                }
            }

            // --- Availability and project bindings for ORG-scoped assets ---
            if (Scope.ORGANIZATION.equals(scope)) {
                String availability = version.getAvailability();
                if (Availability.OPTIONAL.equals(availability)) {
                    if (!enabledByProject.contains(asset.getId())) {
                        excluded.add(Map.of("assetId", asset.getId().toString(),
                                "name", asset.getName(), "reason", "NOT_ENABLED_BY_PROJECT"));
                        continue;
                    }
                }
            }

            // --- Assistant exclusion (applies to both org and project scope) ---
            if (excludedByAssistant.contains(asset.getId())) {
                excluded.add(Map.of("assetId", asset.getId().toString(),
                        "name", asset.getName(), "reason", "ASSISTANT_EXCLUDED"));
                continue;
            }

            // Extract content
            String content = "";
            if (version.getSourceDetails() != null) {
                Object contentObj = version.getSourceDetails().get("content");
                if (contentObj instanceof String s) {
                    content = s;
                }
            }

            int tokenCount = estimateTokens(content);
            int priority = version.getPriority() != null ? version.getPriority() : 0;
            // Org scope gets base priority 1000, project scope gets base 2000 (project overrides org)
            int effectivePriority = priority + (Scope.ORGANIZATION.equals(scope) ? 1000 : 2000);

            entries.add(new AssembledContext.InstructionEntry(
                    asset.getId(),
                    version.getId(),
                    asset.getName(),
                    scope,
                    asset.getCategory(),
                    version.getSourceType(),
                    "INLINE".equals(version.getSourceType()) ? content : null,
                    version.getGitCommit(),
                    version.getInlineVersion(),
                    effectivePriority,
                    tokenCount,
                    version.getAvailability()));
        }
    }

    /**
     * Load instruction entries from pinned version IDs (PINNED_AT_START mode).
     * Skips live resolution, applicability filtering, and activation rules —
     * the snapshot captures what was active at creation time.
     */
    private List<AssembledContext.InstructionEntry> loadPinnedInstructions(
            List<UUID> versionIds,
            List<Map<String, Object>> excluded) {

        List<AssembledContext.InstructionEntry> entries = new ArrayList<>();
        for (UUID versionId : versionIds) {
            InstructionAssetVersion version = instructionAssetVersionRepository
                    .findById(versionId).orElse(null);
            if (version == null) {
                excluded.add(Map.of("versionId", versionId.toString(), "reason", "NOT_FOUND"));
                continue;
            }
            InstructionAsset asset = instructionAssetRepository
                    .findById(version.getAssetId()).orElse(null);
            if (asset == null) {
                excluded.add(Map.of("versionId", versionId.toString(), "reason", "ASSET_NOT_FOUND"));
                continue;
            }

            String content = "";
            if (version.getSourceDetails() != null) {
                Object contentObj = version.getSourceDetails().get("content");
                if (contentObj instanceof String s) {
                    content = s;
                }
            }

            int tokenCount = estimateTokens(content);
            int priority = version.getPriority() != null ? version.getPriority() : 0;
            int effectivePriority = priority + (Scope.ORGANIZATION.equals(asset.getScope()) ? 1000 : 2000);

            entries.add(new AssembledContext.InstructionEntry(
                    asset.getId(),
                    version.getId(),
                    asset.getName(),
                    asset.getScope(),
                    asset.getCategory(),
                    version.getSourceType(),
                    "INLINE".equals(version.getSourceType()) ? content : null,
                    version.getGitCommit(),
                    version.getInlineVersion(),
                    effectivePriority,
                    tokenCount,
                    version.getAvailability()));
        }
        return entries;
    }

    /**
     * Get the set of instruction asset IDs enabled by project bindings.
     */
    private Set<UUID> getEnabledInstructionBindings(UUID projectId) {
        if (projectId == null) return Set.of();
        return projectInstructionBindingRepository
                .findByProjectIdAndEnabledTrue(projectId)
                .stream()
                .map(ProjectInstructionBinding::getInstructionAssetId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Get the set of target IDs excluded by assistant context bindings.
     */
    private Set<UUID> getExcludedByAssistant(UUID assistantVersionId, String bindingType) {
        if (assistantVersionId == null) return Set.of();
        return assistantContextBindingRepository
                .findByAssistantVersionIdAndBindingType(assistantVersionId, bindingType)
                .stream()
                .filter(b -> !b.getEnabled())
                .map(AssistantContextBinding::getTargetId)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Resolve active knowledge sources for the project.
     */
    private List<AssembledContext.KnowledgeSourceRef> resolveKnowledgeSources(
            UUID projectId, UUID assistantVersionId) {

        List<AssembledContext.KnowledgeSourceRef> result = new ArrayList<>();

        // Org-scoped knowledge sources
        for (KnowledgeSource source : knowledgeSourceRepository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION)) {
            if (!EntityStatus.ACTIVE.equals(source.getStatus())) continue;
            result.add(buildSourceRef(source));
        }

        // Project-scoped knowledge sources
        if (projectId != null) {
            for (KnowledgeSource source : knowledgeSourceRepository.findByScopeAndProjectId(Scope.PROJECT, projectId)) {
                if (!EntityStatus.ACTIVE.equals(source.getStatus())) continue;
                result.add(buildSourceRef(source));
            }
        }

        // Filter by assistant context bindings (excluded knowledge sources)
        if (assistantVersionId != null) {
            Set<UUID> excludedSources = getExcludedByAssistant(assistantVersionId, "KNOWLEDGE_SOURCE");
            result = result.stream()
                    .filter(ref -> !excludedSources.contains(ref.sourceId()))
                    .toList();
        }

        return result;
    }

    /**
     * Build a KnowledgeSourceRef from a KnowledgeSource entity.
     */
    private AssembledContext.KnowledgeSourceRef buildSourceRef(KnowledgeSource source) {
        String providerType = "UNKNOWN";
        String datasetName = null;

        // Look up the provider version to get the provider type
        if (source.getProviderVersionId() != null) {
            KnowledgeProviderVersion pv = knowledgeProviderVersionRepository
                    .findById(source.getProviderVersionId()).orElse(null);
            if (pv != null) {
                KnowledgeProvider provider = knowledgeProviderRepository
                        .findById(pv.getProviderId()).orElse(null);
                if (provider != null) {
                    providerType = provider.getType();
                }
            }
        }

        // Extract datasetName from config
        if (source.getConfig() != null) {
            Object dataset = source.getConfig().get("datasetName");
            if (dataset instanceof String s) {
                datasetName = s;
            }
        }

        return new AssembledContext.KnowledgeSourceRef(
                source.getId(),
                source.getName(),
                source.getProviderVersionId(),
                providerType,
                datasetName,
                source.getAvailability());
    }

    /**
     * Write a context manifest record.
     */
    private ContextManifest writeManifest(
            UUID conversationId, UUID messageId, long sequenceNo,
            String profileCode, String contextPinning,
            int totalTokens, int budgetTokens, boolean truncated,
            List<AssembledContext.InstructionEntry> included,
            List<Map<String, Object>> excluded,
            List<Map<String, Object>> truncatedList,
            List<AssembledContext.KnowledgeSourceRef> knowledgeSources) {

        // Build instructions_included JSONB
        List<Map<String, Object>> includedJson = included.stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("assetId", e.assetId().toString());
                    m.put("versionId", e.versionId().toString());
                    m.put("name", e.name());
                    m.put("scope", e.scope());
                    m.put("category", e.category());
                    m.put("sourceType", e.sourceType());
                    if (e.gitCommit() != null) m.put("gitCommit", e.gitCommit());
                    if (e.inlineVersion() != null) m.put("inlineVersion", e.inlineVersion());
                    m.put("priority", e.priority());
                    m.put("tokenCount", e.tokenCount());
                    return m;
                })
                .toList();

        // Build knowledge_retrieved JSONB
        List<Map<String, Object>> knowledgeJson = knowledgeSources.stream()
                .map(s -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("sourceId", s.sourceId().toString());
                    m.put("sourceName", s.sourceName());
                    m.put("providerType", s.providerType());
                    return m;
                })
                .toList();

        ContextManifest manifest = new ContextManifest();
        manifest.setSessionId(conversationId);
        manifest.setServiceType("CONVERSATION");
        manifest.setMessageId(messageId);
        manifest.setSequenceNo(sequenceNo);
        manifest.setGovernanceProfileCode(profileCode);
        manifest.setContextPinning(contextPinning);
        manifest.setTotalTokens(totalTokens);
        manifest.setBudgetTokens(budgetTokens);
        manifest.setTruncated(truncated);
        manifest.setInstructionsIncluded(includedJson);
        manifest.setInstructionsExcluded(excluded.isEmpty() ? null : excluded);
        manifest.setInstructionsTruncated(truncatedList.isEmpty() ? null : truncatedList);
        manifest.setKnowledgeRetrieved(knowledgeJson.isEmpty() ? null : knowledgeJson);

        return contextManifestRepository.save(manifest);
    }

    /**
     * Estimate token count from content (rough: 4 chars per token).
     */
    private int estimateTokens(String content) {
        if (content == null || content.isEmpty()) return 0;
        return (content.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * Get an integer setting from project_settings, falling back to system default.
     */
    private int getProjectIntSetting(UUID projectId, String key, int defaultValue) {
        if (projectId == null) return defaultValue;
        return projectSettingRepository
                .findByProjectIdAndSettingKey(projectId, key)
                .map(setting -> {
                    String val = setting.getSettingValue();
                    if (val == null || val.isBlank()) return defaultValue;
                    try {
                        return Integer.parseInt(val.trim());
                    } catch (NumberFormatException e) {
                        return defaultValue;
                    }
                })
                .orElse(defaultValue);
    }
}