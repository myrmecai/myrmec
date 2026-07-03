// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Instruction Asset Service — CRUD for instruction assets, version management.
 *
 * <p>Implements the versioned entity pattern: create draft, publish, archive.
 * Instruction assets are AI instructions with categories, source types (INLINE/GIT),
 * and applicability rules.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InstructionAssetService {

    private final InstructionAssetRepository repository;
    private final InstructionAssetVersionRepository versionRepository;
    private final AuditEventService auditEventService;

    // ---- read paths -------------------------------------------------

    @Transactional(readOnly = true)
    public List<InstructionAsset> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull("ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<InstructionAsset> findAllProjectScoped(UUID projectId) {
        return repository.findByScopeAndProjectId("PROJECT", projectId);
    }

    @Transactional(readOnly = true)
    public InstructionAsset findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("InstructionAsset", id));
    }

    @Transactional(readOnly = true)
    public InstructionAssetVersion getPublishedVersion(UUID assetId) {
        return versionRepository.findByAssetIdAndStatus(assetId, "PUBLISHED")
                .orElseThrow(() -> ResourceNotFoundException.of("InstructionAssetVersion",
                        "assetId=" + assetId + ", status=PUBLISHED"));
    }

    @Transactional(readOnly = true)
    public InstructionAssetVersion getDraftVersion(UUID assetId) {
        return versionRepository.findByAssetIdAndStatus(assetId, "DRAFT").orElse(null);
    }

    // ---- create ------------------------------------------------------

    @Transactional
    public InstructionAsset create(String scope, UUID projectId, String name, String description,
                                   String category, UUID actorId, String actorDisplayName) {
        boolean exists = projectId == null
                ? repository.existsByScopeAndProjectIdIsNullAndName(scope, name)
                : repository.existsByScopeAndProjectIdAndName(scope, projectId, name);
        if (exists) {
            throw BadRequestException.forField("name", "DUPLICATE_CODE",
                    "An instruction asset with this name already exists in this scope.");
        }

        InstructionAsset asset = new InstructionAsset();
        asset.setScope(scope);
        asset.setProjectId(projectId);
        asset.setName(name);
        asset.setDescription(description);
        asset.setCategory(category);
        asset.setStatus("INCOMPLETE");
        asset.setCreatedBy(actorId);

        InstructionAsset saved = repository.save(asset);
        log.info("Created instruction asset: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent(
                "instruction_asset", saved.getId(), "CREATED",
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name, "category", category), null);

        return saved;
    }

    // ---- version management ------------------------------------------

    @Transactional
    public InstructionAssetVersion createDraft(UUID assetId, String sourceType,
                                                Map<String, Object> sourceDetails,
                                                UUID connectionConfigId,
                                                Map<String, Object> applicability,
                                                String availability, Integer priority,
                                                Map<String, Object> activationRules,
                                                UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);

        // Check if a draft already exists
        InstructionAssetVersion existingDraft = getDraftVersion(assetId);
        if (existingDraft != null) {
            throw new BadRequestException("A draft version already exists for this instruction asset.");
        }

        InstructionAssetVersion publishedVersion = versionRepository
                .findByAssetIdAndStatus(assetId, "PUBLISHED").orElse(null);

        int versionNumber = publishedVersion != null ? publishedVersion.getVersionNumber() + 1 : 1;

        InstructionAssetVersion draft = new InstructionAssetVersion();
        draft.setAssetId(assetId);
        draft.setVersionNumber(versionNumber);
        draft.setParentVersionId(publishedVersion != null ? publishedVersion.getId() : null);
        draft.setStatus("DRAFT");
        draft.setSourceType(sourceType);
        draft.setSourceDetails(sourceDetails);
        draft.setConnectionConfigId(connectionConfigId);
        draft.setApplicability(applicability != null ? applicability : Map.of());
        draft.setAvailability(availability != null ? availability : "OPTIONAL");
        draft.setPriority(priority != null ? priority : 0);
        draft.setActivationRules(activationRules);
        draft.setDraftOwnerId(actorId);

        InstructionAssetVersion saved = versionRepository.save(draft);
        log.info("Created draft version {} for instruction asset: {}", versionNumber, assetId);

        auditEventService.recordEvent(
                "instruction_asset", assetId, "DRAFT_CREATED",
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null, Map.of("version_number", versionNumber), null);

        return saved;
    }

    @Transactional
    public InstructionAssetVersion publishDraft(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        InstructionAssetVersion draft = getDraftVersion(assetId);

        if (draft == null) {
            throw new BadRequestException("No draft version exists to publish.");
        }

        // Archive the current published version if one exists
        InstructionAssetVersion currentPublished = versionRepository
                .findByAssetIdAndStatus(assetId, "PUBLISHED").orElse(null);
        if (currentPublished != null) {
            currentPublished.setStatus("ARCHIVED");
            versionRepository.save(currentPublished);
        }

        // Publish the draft
        draft.setStatus("PUBLISHED");
        draft.setDraftOwnerId(null);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(actorId);
        InstructionAssetVersion saved = versionRepository.save(draft);

        // Update parent
        asset.setCurrentVersionId(saved.getId());
        asset.setStatus("ACTIVE");
        asset.setPublishedAt(Instant.now());
        asset.setPublishedBy(actorId);
        repository.save(asset);

        log.info("Published instruction asset version {} (id: {})", draft.getVersionNumber(), assetId);

        auditEventService.recordEvent(
                "instruction_asset", assetId, "PUBLISHED",
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null, Map.of("version_number", draft.getVersionNumber()), null);

        return saved;
    }

    @Transactional
    public void discardDraft(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        InstructionAssetVersion draft = getDraftVersion(assetId);

        if (draft == null) {
            throw new BadRequestException("No draft version exists to discard.");
        }

        versionRepository.delete(draft);
        log.info("Discarded draft for instruction asset: {}", assetId);

        auditEventService.recordEvent(
                "instruction_asset", assetId, "DRAFT_DISCARDED",
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                draft.getId(), null, null, null, null);
    }

    // ---- lifecycle ---------------------------------------------------

    @Transactional
    public InstructionAsset disable(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        asset.setStatus("DISABLED");
        InstructionAsset saved = repository.save(asset);
        log.info("Disabled instruction asset: {}", assetId);

        auditEventService.recordEvent(
                "instruction_asset", assetId, "DISABLED",
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_DISABLED", null, null, null);

        return saved;
    }

    @Transactional
    public InstructionAsset reenable(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        asset.setStatus("ACTIVE");
        InstructionAsset saved = repository.save(asset);
        log.info("Re-enabled instruction asset: {}", assetId);

        auditEventService.recordEvent(
                "instruction_asset", assetId, "REENABLED",
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_REENABLED", null, null, null);

        return saved;
    }

    @Transactional
    public InstructionAsset archive(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        asset.setStatus("ARCHIVED");
        InstructionAsset saved = repository.save(asset);
        log.info("Archived instruction asset: {}", assetId);

        auditEventService.recordEvent(
                "instruction_asset", assetId, "ARCHIVED",
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_ARCHIVED", null, null, null);

        return saved;
    }
}