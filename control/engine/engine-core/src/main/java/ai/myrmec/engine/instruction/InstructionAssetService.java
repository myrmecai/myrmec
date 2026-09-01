// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction;

import ai.myrmec.engine._system.common.DomainConstants;
import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.DomainConstants.Availability;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.governance.GovernancePolicyEnforcer;
import ai.myrmec.engine.governance.GovernanceScope;
import ai.myrmec.engine.governance.ProductFeature;
import ai.myrmec.engine.instruction.dto.InstructionAssetResponse;
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
    private final GovernancePolicyEnforcer governanceEnforcer;

    // ---- read paths -------------------------------------------------

    @Transactional(readOnly = true)
    public List<InstructionAsset> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION);
    }

    /**
     * List all org-scoped instruction assets with their published version's
     * availability populated.  Used by the admin list endpoint so the UI can
     * show REQUIRED/OPTIONAL badges without a separate version fetch per asset.
     */
    @Transactional(readOnly = true)
    public List<InstructionAssetResponse> findAllOrgScopedWithAvailability() {
        return repository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION).stream()
                .map(a -> InstructionAssetResponse.from(a, getPublishedVersionOrNull(a.getId()), getDraftVersion(a.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<InstructionAsset> findAllProjectScoped(UUID projectId) {
        return repository.findByScopeAndProjectId(Scope.PROJECT, projectId);
    }

    @Transactional(readOnly = true)
    public InstructionAsset findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("InstructionAsset", id));
    }

    @Transactional(readOnly = true)
    public InstructionAssetVersion getPublishedVersion(UUID assetId) {
        return versionRepository.findByAssetIdAndStatus(assetId, EntityStatus.PUBLISHED)
                .orElseThrow(() -> ResourceNotFoundException.of("InstructionAssetVersion",
                        "assetId=" + assetId + ", status=PUBLISHED"));
    }

    @Transactional(readOnly = true)
    public InstructionAssetVersion getDraftVersion(UUID assetId) {
        return versionRepository.findByAssetIdAndStatus(assetId, EntityStatus.DRAFT).orElse(null);
    }

    /**
     * Get the published version of an instruction asset, or {@code null} if
     * no published version exists.  Unlike {@link #getPublishedVersion(UUID)},
     * this method does not throw when the version is missing.
     */
    @Transactional(readOnly = true)
    public InstructionAssetVersion getPublishedVersionOrNull(UUID assetId) {
        return versionRepository.findByAssetIdAndStatus(assetId, EntityStatus.PUBLISHED).orElse(null);
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
        asset.setStatus(EntityStatus.INCOMPLETE);
        asset.setCreatedBy(actorId);
        asset.setUpdatedBy(actorId);

        InstructionAsset saved = repository.save(asset);
        log.info("Created instruction asset: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent(
                ResourceType.INSTRUCTION_ASSET, saved.getId(), AuditAction.CREATED,
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name, "category", category), null);

        return saved;
    }

    // ---- update parent ------------------------------------------------

    @Transactional
    public InstructionAsset update(UUID assetId, String name, String description, String category,
                                   UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);

        if (name != null && !name.isBlank() && !name.equals(asset.getName())) {
            boolean exists = asset.getProjectId() == null
                    ? repository.existsByScopeAndProjectIdIsNullAndName(asset.getScope(), name)
                    : repository.existsByScopeAndProjectIdAndName(asset.getScope(), asset.getProjectId(), name);
            if (exists) {
                throw BadRequestException.forField("name", "DUPLICATE_CODE",
                        "An instruction asset with this name already exists in this scope.");
            }
            asset.setName(name);
        }

        if (description != null) {
            asset.setDescription(description);
        }

        if (category != null && !category.isBlank()) {
            asset.setCategory(category);
        }

        asset.setUpdatedBy(actorId);
        InstructionAsset saved = repository.save(asset);
        log.info("Updated instruction asset: {} (id: {})", saved.getName(), assetId);

        auditEventService.recordEvent(
                ResourceType.INSTRUCTION_ASSET, assetId, AuditAction.UPDATED,
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                null, null, null, Map.of("name", saved.getName()), null);

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

        // Governance: INSTRUCTION_SOURCES
        governanceEnforcer.assertAllowed(
            asset.getProjectId() != null
                ? GovernanceScope.ofProject(asset.getProjectId())
                : GovernanceScope.orgScope(),
            ProductFeature.INSTRUCTION_SOURCES,
            sourceType);

        // Governance: INLINE_INSTRUCTIONS_SCOPE (only when sourceType=INLINE)
        // This is a threshold feature (NONE < PROJECT_SERVICE < ALL): the profile's
        // value must permit at least the scope the asset lives in. Use
        // assertPermitted, not assertAllowed — ALL permits PROJECT_SERVICE.
        if ("INLINE".equals(sourceType)) {
            governanceEnforcer.assertPermitted(
                asset.getProjectId() != null
                    ? GovernanceScope.ofProject(asset.getProjectId())
                    : GovernanceScope.orgScope(),
                ProductFeature.INLINE_INSTRUCTIONS_SCOPE,
                asset.getProjectId() != null ? "PROJECT_SERVICE" : "ALL");
        }

        // Check if a draft already exists
        InstructionAssetVersion existingDraft = getDraftVersion(assetId);
        if (existingDraft != null) {
            throw new BadRequestException("A draft version already exists for this instruction asset.");
        }

        InstructionAssetVersion publishedVersion = versionRepository
                .findByAssetIdAndStatus(assetId, EntityStatus.PUBLISHED).orElse(null);

        int versionNumber = publishedVersion != null ? publishedVersion.getVersionNumber() + 1 : 1;

        InstructionAssetVersion draft = new InstructionAssetVersion();
        draft.setAssetId(assetId);
        draft.setVersionNumber(versionNumber);
        draft.setParentVersionId(publishedVersion != null ? publishedVersion.getId() : null);
        draft.setStatus(EntityStatus.DRAFT);
        draft.setSourceType(sourceType);
        draft.setSourceDetails(sourceDetails);
        draft.setConnectionConfigId(connectionConfigId);
        draft.setApplicability(applicability != null ? applicability : Map.of());
        draft.setAvailability(availability != null ? availability : Availability.OPTIONAL);
        draft.setPriority(priority != null ? priority : 0);
        draft.setActivationRules(activationRules);
        draft.setDraftOwnerId(actorId);

        InstructionAssetVersion saved = versionRepository.save(draft);
        log.info("Created draft version {} for instruction asset: {}", versionNumber, assetId);

        auditEventService.recordEvent(
                ResourceType.INSTRUCTION_ASSET, assetId, AuditAction.DRAFT_CREATED,
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
                .findByAssetIdAndStatus(assetId, EntityStatus.PUBLISHED).orElse(null);
        if (currentPublished != null) {
            currentPublished.setStatus(EntityStatus.ARCHIVED);
            versionRepository.save(currentPublished);
        }

        // Publish the draft
        draft.setStatus(EntityStatus.PUBLISHED);
        draft.setDraftOwnerId(null);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(actorId);
        InstructionAssetVersion saved = versionRepository.save(draft);

        // Update parent
        asset.setCurrentVersionId(saved.getId());
        asset.setStatus(EntityStatus.ACTIVE);
        asset.setPublishedAt(Instant.now());
        asset.setPublishedBy(actorId);
        asset.setUpdatedBy(actorId);
        repository.save(asset);

        log.info("Published instruction asset version {} (id: {})", draft.getVersionNumber(), assetId);

        auditEventService.recordEvent(
                ResourceType.INSTRUCTION_ASSET, assetId, EntityStatus.PUBLISHED,
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
                ResourceType.INSTRUCTION_ASSET, assetId, AuditAction.DRAFT_DISCARDED,
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                draft.getId(), null, null, null, null);
    }

    @Transactional
    public InstructionAssetVersion updateDraft(UUID assetId, String sourceType,
                                               Map<String, Object> sourceDetails,
                                               UUID connectionConfigId,
                                               Map<String, Object> applicability,
                                               String availability,
                                               Integer priority,
                                               Map<String, Object> activationRules,
                                               UUID actorId) {
        InstructionAssetVersion draft = getDraftVersion(assetId);
        if (draft == null) {
            throw new BadRequestException("No draft version exists to update.");
        }

        if (sourceType != null) draft.setSourceType(sourceType);
        if (sourceDetails != null) draft.setSourceDetails(sourceDetails);
        if (connectionConfigId != null) draft.setConnectionConfigId(connectionConfigId);
        if (applicability != null) draft.setApplicability(applicability);
        if (availability != null) draft.setAvailability(availability);
        if (priority != null) draft.setPriority(priority);
        if (activationRules != null) draft.setActivationRules(activationRules);

        return versionRepository.save(draft);
    }

    // ---- lifecycle ---------------------------------------------------

    @Transactional
    public InstructionAsset disable(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        asset.setStatus(EntityStatus.DISABLED);
        asset.setUpdatedBy(actorId);
        InstructionAsset saved = repository.save(asset);
        log.info("Disabled instruction asset: {}", assetId);

        auditEventService.recordEvent(
                ResourceType.INSTRUCTION_ASSET, assetId, EntityStatus.DISABLED,
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_DISABLED, null, null, null);

        return saved;
    }

    @Transactional
    public InstructionAsset reenable(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        asset.setStatus(EntityStatus.ACTIVE);
        asset.setUpdatedBy(actorId);
        InstructionAsset saved = repository.save(asset);
        log.info("Re-enabled instruction asset: {}", assetId);

        auditEventService.recordEvent(
                ResourceType.INSTRUCTION_ASSET, assetId, AuditAction.REENABLED,
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_REENABLED, null, null, null);

        return saved;
    }

    @Transactional
    public InstructionAsset archive(UUID assetId, UUID actorId, String actorDisplayName) {
        InstructionAsset asset = findById(assetId);
        asset.setStatus(EntityStatus.ARCHIVED);
        asset.setUpdatedBy(actorId);
        InstructionAsset saved = repository.save(asset);
        log.info("Archived instruction asset: {}", assetId);

        auditEventService.recordEvent(
                ResourceType.INSTRUCTION_ASSET, assetId, EntityStatus.ARCHIVED,
                asset.getScope(), asset.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_ARCHIVED, null, null, null);

        return saved;
    }
}