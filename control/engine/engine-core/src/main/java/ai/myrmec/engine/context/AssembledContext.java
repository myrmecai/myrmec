// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of {@link ContextBuilder#assemble} — the fully assembled AI context
 * for a single turn, with instructions sorted by priority and capped by token budget.
 *
 * @param instructions       ordered list of instruction entries (highest priority first)
 * @param knowledgeSources   active knowledge source references for retrieval
 * @param governanceProfileCode the effective governance profile code
 * @param contextPinning     the pinning mode (PINNED_AT_START or IMMEDIATE_EFFECT)
 * @param totalTokens        total estimated tokens in the assembled context
 * @param budgetTokens       the token budget that was applied
 * @param truncated          whether any instructions were truncated due to budget
 * @param manifest           the context manifest audit record (null if not written)
 */
public record AssembledContext(
        List<InstructionEntry> instructions,
        List<KnowledgeSourceRef> knowledgeSources,
        String governanceProfileCode,
        String contextPinning,
        int totalTokens,
        int budgetTokens,
        boolean truncated,
        ContextManifest manifest) {

    /**
     * A single instruction entry in the assembled context.
     *
     * @param assetId      the instruction asset id
     * @param versionId    the published version id
     * @param name         the asset name
     * @param scope        ORGANIZATION or PROJECT
     * @param category     the instruction category
     * @param sourceType    INLINE or GIT
     * @param content       the instruction content (for INLINE) or null (for GIT)
     * @param gitCommit    the pinned git commit (for GIT) or null
     * @param inlineVersion the inline version number (for INLINE) or null
     * @param priority      the effective priority (scope base + offset)
     * @param tokenCount   estimated token count
     */
    public record InstructionEntry(
            UUID assetId,
            UUID versionId,
            String name,
            String scope,
            String category,
            String sourceType,
            String content,
            String gitCommit,
            Integer inlineVersion,
            int priority,
            int tokenCount) {
    }

    /**
     * A reference to an active knowledge source available for retrieval.
     *
     * @param sourceId          the knowledge source id
     * @param sourceName         the source name
     * @param providerVersionId  the pinned provider version id
     * @param providerType       MANAGED or EXTERNAL
     * @param datasetName        the dataset name for retrieval
     * @param availability       GLOBAL or PROJECT_SCOPED
     */
    public record KnowledgeSourceRef(
            UUID sourceId,
            String sourceName,
            UUID providerVersionId,
            String providerType,
            String datasetName,
            String availability) {
    }
}