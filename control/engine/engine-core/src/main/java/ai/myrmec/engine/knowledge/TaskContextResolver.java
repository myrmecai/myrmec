// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetRepository;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.instruction.InstructionAssetVersionRepository;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.websocket.message.payload.TaskContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TaskContextResolver — resolves the AI context for a workflow task.
 *
 * <p>Replaces the old knowledge-document-based resolver with the new
 * instruction assets system. Finds published instruction assets at both
 * org and Team scope, builds KnowledgeEntry objects from their
 * published versions, and resolves workspace config from the Team.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskContextResolver {

    private final InstructionAssetRepository instructionAssetRepository;
    private final InstructionAssetVersionRepository instructionAssetVersionRepository;
    private final TeamRepository teamRepository;

    /**
     * Resolve the task context for a workflow step.
     *
     * @param teamId    the Team the workflow belongs to
     * @param stepId        the step identifier (unused for now, future: step-specific filtering)
     * @param artifactsRepo the workflow artifacts repo config (JSON string, unused — workspace comes from Team)
     * @return a TaskContext with knowledge entries and optional workspace config
     */
    public TaskContext resolve(UUID teamId, String stepId, String artifactsRepo) {
        List<TaskContext.KnowledgeEntry> entries = new ArrayList<>();
        int charCount = 0;

        // 1. Find org-scoped published instruction assets
        List<InstructionAsset> orgAssets = instructionAssetRepository
                .findByScopeAndTeamIdIsNull(Scope.ORGANIZATION);
        for (InstructionAsset asset : orgAssets) {
            if (!EntityStatus.ACTIVE.equals(asset.getStatus()) || asset.getCurrentVersionId() == null) {
                continue;
            }
            TaskContext.KnowledgeEntry entry = buildEntry(asset);
            if (entry != null) {
                entries.add(entry);
                if (entry.getContent() != null) {
                    charCount += entry.getContent().length();
                }
            }
        }

        // 2. Find Team-scoped published instruction assets
        List<InstructionAsset> projectAssets = instructionAssetRepository
                .findByScopeAndTeamId(Scope.TEAM, teamId);
        for (InstructionAsset asset : projectAssets) {
            if (!EntityStatus.ACTIVE.equals(asset.getStatus()) || asset.getCurrentVersionId() == null) {
                continue;
            }
            TaskContext.KnowledgeEntry entry = buildEntry(asset);
            if (entry != null) {
                entries.add(entry);
                if (entry.getContent() != null) {
                    charCount += entry.getContent().length();
                }
            }
        }

        // 3. Sort by priority (higher first)
        entries.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));

        // 4. Resolve workspace config from team
        TaskContext.WorkspaceConfig workspace = null;
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team != null && team.getWorkspaceRepoUrl() != null) {
            workspace = TaskContext.WorkspaceConfig.builder()
                    .repoUrl(team.getWorkspaceRepoUrl())
                    .branch(team.getWorkspaceRepoBranch() != null
                            ? team.getWorkspaceRepoBranch() : "main")
                    .build();
        }

        log.debug("Resolved task context for Team {}: {} knowledge entries, {} chars, workspace={}",
                teamId, entries.size(), charCount, workspace != null ? "yes" : "no");

        return TaskContext.builder()
                .knowledge(entries)
                .knowledgeCharCount(charCount)
                .workspace(workspace)
                .build();
    }

    /**
     * Build a KnowledgeEntry from an instruction asset's published version.
     */
    private TaskContext.KnowledgeEntry buildEntry(InstructionAsset asset) {
        InstructionAssetVersion version = instructionAssetVersionRepository
                .findByAssetIdAndStatus(asset.getId(), EntityStatus.PUBLISHED)
                .orElse(null);
        if (version == null) {
            return null;
        }

        // Extract content from source_details
        String content = "";
        if (version.getSourceDetails() != null) {
            Object contentObj = version.getSourceDetails().get("content");
            if (contentObj instanceof String s) {
                content = s;
            }
        }

        return TaskContext.KnowledgeEntry.builder()
                .category(asset.getCategory())
                .name(asset.getName())
                .content(content)
                .priority(version.getPriority() != null ? version.getPriority() : 0)
                .build();
    }
}