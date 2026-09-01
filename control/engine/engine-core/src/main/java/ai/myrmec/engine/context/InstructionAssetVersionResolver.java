// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine._system.common.DomainConstants.Availability;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine.instruction.InstructionAsset;
import ai.myrmec.engine.instruction.InstructionAssetRepository;
import ai.myrmec.engine.instruction.InstructionAssetVersion;
import ai.myrmec.engine.instruction.InstructionAssetVersionRepository;
import ai.myrmec.engine.project.ProjectInstructionBinding;
import ai.myrmec.engine.project.ProjectInstructionBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the instruction asset version IDs that are active for a project scope
 * at this point in time.
 *
 * <p>Mirrors the live-resolution rules in {@link ContextBuilder#collectInstructions}
 * without depending on it, so callers can pin the version list in
 * {@code Session.contextPins} or in a {@link ContextSnapshot}.</p>
 *
 * <p>Resolution rules (matching {@code ContextBuilder}):
 * <ul>
 *   <li>All org-scoped ACTIVE assets whose published version has availability REQUIRED.</li>
 *   <li>Org-scoped ACTIVE assets whose published version has availability OPTIONAL,
 *       only if the project has an enabled binding for that asset.</li>
 *   <li>All project-scoped ACTIVE assets for the given project with a PUBLISHED version.</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstructionAssetVersionResolver {

    private final InstructionAssetRepository instructionAssetRepository;
    private final InstructionAssetVersionRepository instructionAssetVersionRepository;
    private final ProjectInstructionBindingRepository projectInstructionBindingRepository;

    /**
     * Return the published version IDs for all instruction assets that would be
     * included when assembling context for the given project at the current time.
     */
    public List<UUID> resolveActiveVersionIdsForProject(UUID projectId) {
        List<UUID> versionIds = new ArrayList<>();

        // Org-scoped ACTIVE assets
        List<InstructionAsset> orgAssets = instructionAssetRepository
                .findByScopeAndProjectIdIsNull(Scope.ORGANIZATION);

        // Enabled project bindings (for OPTIONAL org assets)
        Set<UUID> enabledAssetIds = projectInstructionBindingRepository
                .findByProjectIdAndEnabledTrue(projectId).stream()
                .map(ProjectInstructionBinding::getInstructionAssetId)
                .collect(Collectors.toSet());

        for (InstructionAsset asset : orgAssets) {
            if (!EntityStatus.ACTIVE.equals(asset.getStatus())) continue;

            InstructionAssetVersion version = instructionAssetVersionRepository
                    .findByAssetIdAndStatus(asset.getId(), EntityStatus.PUBLISHED)
                    .orElse(null);
            if (version == null) continue;

            String availability = version.getAvailability();
            if (Availability.REQUIRED.equals(availability)) {
                versionIds.add(version.getId());
            } else if (Availability.OPTIONAL.equals(availability) && enabledAssetIds.contains(asset.getId())) {
                versionIds.add(version.getId());
            }
        }

        // Project-scoped ACTIVE assets
        List<InstructionAsset> projectAssets = instructionAssetRepository
                .findByScopeAndProjectId(Scope.PROJECT, projectId);

        for (InstructionAsset asset : projectAssets) {
            if (!EntityStatus.ACTIVE.equals(asset.getStatus())) continue;

            InstructionAssetVersion version = instructionAssetVersionRepository
                    .findByAssetIdAndStatus(asset.getId(), EntityStatus.PUBLISHED)
                    .orElse(null);
            if (version == null) continue;

            versionIds.add(version.getId());
        }

        log.debug("Resolved {} instruction asset version IDs for project {}",
                versionIds.size(), projectId);
        return versionIds;
    }
}