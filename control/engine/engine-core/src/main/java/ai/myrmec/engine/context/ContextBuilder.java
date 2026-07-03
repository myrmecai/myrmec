// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine.assistant.AssistantContextBinding;
import ai.myrmec.engine.assistant.AssistantContextBindingRepository;
import ai.myrmec.engine.governance.GovernanceProfileService;
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

    private final GovernanceProfileService governanceProfileService;
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

        // 1. Resolve governance profile
        String profileCode = systemSettingService.getString(GOVERNANCE_PROFILE_KEY, DEFAULT_GOVERNANCE_PROFILE);
        Map<String, Object> policies = governanceProfileService.getEffectivePolicies(profileCode);
        String contextPinning = (String) policies.getOrDefault("contextPinning", "IMMEDIATE_EFFECT");

        // 2. Resolve token budget from project settings
        int budgetTokens = getProjectIntSetting(projectId, CONTEXT_TOKEN_BUDGET_KEY, DEFAULT_TOKEN_BUDGET);

        // 3. Collect instruction entries
        List<AssembledContext.InstructionEntry> allInstructions = new ArrayList<>();
        List<Map<String, Object>> excludedInstructions = new ArrayList<>();

        // 3a. Org-scoped published instruction assets
        collectInstructions("ORGANIZATION", null, serviceType, allInstructions, excludedInstructions);

        // 3b. Project-scoped published instruction assets
        collectInstructions("PROJECT", projectId, serviceType, allInstructions, excludedInstructions);

        // 3c. Apply project instruction bindings (enable org OPTIONAL assets)
        Set<UUID> enabledByProject = getEnabledInstructionBindings(projectId);

        // 3d. Apply assistant context bindings (opt out of OPTIONAL assets)
        Set<UUID> excludedByAssistant = getExcludedByAssistant(assistantVersionId, "INSTRUCTION_ASSET");

        // 3e. Filter instructions based on availability, bindings, and assistant overrides
        List<AssembledContext.InstructionEntry> includedInstructions = new ArrayList<>();
        for (AssembledContext.InstructionEntry entry : allInstructions) {
            // Check if excluded by assistant binding
            if (excludedByAssistant.contains(entry.assetId())) {
                excludedInstructions.add(Map.of(
                        "assetId", entry.assetId().toString(),
                        "name", entry.name(),
                        "reason", "ASSISTANT_EXCLUDED"));
                continue;
            }
            includedInstructions.add(entry);
        }

        // 4. Sort by priority (higher first)
        includedInstructions.sort(Comparator.comparingInt(
                AssembledContext.InstructionEntry::priority).reversed());

        // 5. Estimate token counts and apply budget
        int totalTokens = 0;
        List<AssembledContext.InstructionEntry> budgetCapped = new ArrayList<>();
        List<Map<String, Object>> truncatedInstructions = new ArrayList<>();
        boolean truncated = false;

        for (AssembledContext.InstructionEntry entry : includedInstructions) {
            int entryTokens = entry.tokenCount();
            if (totalTokens + entryTokens > budgetTokens) {
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
                manifest);
    }

    /**
     * Collect published instruction entries for a given scope.
     */
    private void collectInstructions(
            String scope, UUID projectId, String serviceType,
            List<AssembledContext.InstructionEntry> entries,
            List<Map<String, Object>> excluded) {

        List<InstructionAsset> assets = projectId == null
                ? instructionAssetRepository.findByScopeAndProjectIdIsNull(scope)
                : instructionAssetRepository.findByScopeAndProjectId(scope, projectId);

        for (InstructionAsset asset : assets) {
            if (!"ACTIVE".equals(asset.getStatus()) || asset.getCurrentVersionId() == null) {
                if (!"ACTIVE".equals(asset.getStatus())) {
                    excluded.add(Map.of("assetId", asset.getId().toString(),
                            "name", asset.getName(), "reason", "DISABLED"));
                }
                continue;
            }

            InstructionAssetVersion version = instructionAssetVersionRepository
                    .findByAssetIdAndStatus(asset.getId(), "PUBLISHED")
                    .orElse(null);
            if (version == null) {
                continue;
            }

            // Check applicability — if the version has applicability set, the service type must match
            if (version.getApplicability() != null && !version.getApplicability().isEmpty()) {
                Object applicabilityObj = version.getApplicability().get(serviceType);
                if (applicabilityObj == null && !version.getApplicability().isEmpty()) {
                    // Check if the applicability map has any key matching the service type
                    boolean matches = version.getApplicability().keySet().stream()
                            .anyMatch(k -> k.toString().equalsIgnoreCase(serviceType));
                    if (!matches) {
                        excluded.add(Map.of("assetId", asset.getId().toString(),
                                "name", asset.getName(), "reason", "NOT_APPLICABLE"));
                        continue;
                    }
                }
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
            int effectivePriority = priority + ("ORGANIZATION".equals(scope) ? 1000 : 2000);

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
                    tokenCount));
        }
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
        for (KnowledgeSource source : knowledgeSourceRepository.findByScopeAndProjectIdIsNull("ORGANIZATION")) {
            if (!"ACTIVE".equals(source.getStatus())) continue;
            result.add(buildSourceRef(source));
        }

        // Project-scoped knowledge sources
        if (projectId != null) {
            for (KnowledgeSource source : knowledgeSourceRepository.findByScopeAndProjectId("PROJECT", projectId)) {
                if (!"ACTIVE".equals(source.getStatus())) continue;
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
        manifest.setConversationId(conversationId);
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