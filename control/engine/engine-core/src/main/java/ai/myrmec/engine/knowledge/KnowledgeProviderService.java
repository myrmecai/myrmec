// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.governance.GovernancePolicyEnforcer;
import ai.myrmec.engine.governance.GovernanceScope;
import ai.myrmec.engine.governance.ProductFeature;
import ai.myrmec.engine.project.ProjectProviderBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeProviderService {

    private final KnowledgeProviderRepository repository;
    private final KnowledgeProviderVersionRepository versionRepository;
    private final KnowledgeSourceRepository sourceRepository;
    private final DataFeedRepository dataFeedRepository;
    private final ProjectProviderBindingRepository bindingRepository;
    private final AuditEventService auditEventService;
    private final GovernancePolicyEnforcer governanceEnforcer;

    @Transactional(readOnly = true)
    public List<KnowledgeProvider> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeProvider> findAllProjectScoped(UUID projectId) {
        return repository.findByScopeAndProjectId(Scope.PROJECT, projectId);
    }

    @Transactional(readOnly = true)
    public KnowledgeProvider findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeProvider", id));
    }

    @Transactional(readOnly = true)
    public KnowledgeProviderVersion getPublishedVersion(UUID providerId) {
        return versionRepository.findByProviderIdAndStatus(providerId, EntityStatus.PUBLISHED)
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeProviderVersion",
                        "providerId=" + providerId + ", status=PUBLISHED"));
    }

    @Transactional(readOnly = true)
    public KnowledgeProviderVersion getPublishedVersionOrNull(UUID providerId) {
        return versionRepository.findByProviderIdAndStatus(providerId, EntityStatus.PUBLISHED).orElse(null);
    }

    @Transactional(readOnly = true)
    public KnowledgeProviderVersion getDraftVersion(UUID providerId) {
        return versionRepository.findByProviderIdAndStatus(providerId, EntityStatus.DRAFT).orElse(null);
    }

    @Transactional
    public KnowledgeProvider create(String scope, UUID projectId, String name,
                                    String description, String type,
                                    UUID actorId, String actorDisplayName) {
        boolean exists = projectId == null
                ? repository.existsByScopeAndProjectIdIsNullAndName(scope, name)
                : repository.existsByScopeAndProjectIdAndName(scope, projectId, name);
        if (exists) {
            throw BadRequestException.forField("name", "DUPLICATE_CODE",
                    "A knowledge provider with this name already exists in this scope.");
        }

        // Governance: KNOWLEDGE_PROVIDERS — gate provider type by profile
        governanceEnforcer.assertAllowed(
            projectId != null ? GovernanceScope.ofProject(projectId) : GovernanceScope.orgScope(),
            ProductFeature.KNOWLEDGE_PROVIDERS,
            type);

        KnowledgeProvider provider = new KnowledgeProvider();
        provider.setScope(scope);
        provider.setProjectId(projectId);
        provider.setName(name);
        provider.setDescription(description);
        provider.setType(type);
        provider.setStatus(EntityStatus.INCOMPLETE);
        provider.setCreatedBy(actorId);

        KnowledgeProvider saved = repository.save(provider);
        log.info("Created knowledge provider: {} (id: {})", name, saved.getId());

        // Auto-create first Draft version (Pattern 4 §Rule 4)
        KnowledgeProviderVersion draft = new KnowledgeProviderVersion();
        draft.setProviderId(saved.getId());
        draft.setVersionNumber(1);
        draft.setStatus(EntityStatus.DRAFT);
        draft.setDraftOwnerId(actorId);
        versionRepository.save(draft);

        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, saved.getId(), AuditAction.CREATED,
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name, "type", type), null);

        return saved;
    }

    @Transactional
    public KnowledgeProviderVersion createDraft(UUID providerId, UUID connectionConfigId,
                                                 Map<String, Object> config,
                                                 UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);

        KnowledgeProviderVersion existingDraft = getDraftVersion(providerId);
        if (existingDraft != null) {
            throw new BadRequestException("A draft version already exists for this knowledge provider.");
        }

        KnowledgeProviderVersion publishedVersion = versionRepository
                .findByProviderIdAndStatus(providerId, EntityStatus.PUBLISHED).orElse(null);

        int versionNumber = publishedVersion != null ? publishedVersion.getVersionNumber() + 1 : 1;

        KnowledgeProviderVersion draft = new KnowledgeProviderVersion();
        draft.setProviderId(providerId);
        draft.setVersionNumber(versionNumber);
        draft.setParentVersionId(publishedVersion != null ? publishedVersion.getId() : null);
        draft.setStatus(EntityStatus.DRAFT);
        // Clone config from published version if not provided
        if (connectionConfigId != null) {
            draft.setConnectionConfigId(connectionConfigId);
        } else if (publishedVersion != null) {
            draft.setConnectionConfigId(publishedVersion.getConnectionConfigId());
        }
        if (config != null) {
            draft.setConfig(config);
        } else if (publishedVersion != null) {
            draft.setConfig(publishedVersion.getConfig());
        }
        draft.setDraftOwnerId(actorId);

        KnowledgeProviderVersion saved = versionRepository.save(draft);
        log.info("Created draft version {} for knowledge provider: {}", versionNumber, providerId);

        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, AuditAction.DRAFT_CREATED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null, Map.of("version_number", versionNumber), null);

        return saved;
    }

    @Transactional
    public KnowledgeProviderVersion publishDraft(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        KnowledgeProviderVersion draft = getDraftVersion(providerId);

        if (draft == null) {
            throw new BadRequestException("No draft version exists to publish.");
        }

        // Publish gate: validate response mapping fields (nested in config.responseMapping)
        Map<String, Object> config = draft.getConfig();
        if (config == null) {
            throw BadRequestException.forField("config", "REQUIRED",
                    "Configuration is required before publishing.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> responseMapping = (Map<String, Object>) config.get("responseMapping");
        if (responseMapping == null) {
            throw BadRequestException.forField("responseMapping", "REQUIRED",
                    "Response mapping is required before publishing.");
        }
        validateResponseMappingField(responseMapping, "hitsPath");
        validateResponseMappingField(responseMapping, "passagePath");
        validateResponseMappingField(responseMapping, "sourceNamePath");
        validateResponseMappingField(responseMapping, "locatorPath");

        // Snapshot linked knowledge sources into the version config
        List<KnowledgeSource> sources = sourceRepository.findByProviderVersionId(draft.getId());
        if (!sources.isEmpty()) {
            List<Map<String, Object>> sourceSnapshots = new ArrayList<>();
            for (KnowledgeSource s : sources) {
                Map<String, Object> snapshot = new HashMap<>();
                snapshot.put("id", s.getId());
                snapshot.put("name", s.getName());
                snapshot.put("description", s.getDescription());
                snapshot.put("config", s.getConfig());
                sourceSnapshots.add(snapshot);
            }
            config.put("knowledgeSources", sourceSnapshots);
            draft.setConfig(config);
        }

        KnowledgeProviderVersion currentPublished = versionRepository
                .findByProviderIdAndStatus(providerId, EntityStatus.PUBLISHED).orElse(null);
        if (currentPublished != null) {
            currentPublished.setStatus(EntityStatus.ARCHIVED);
            versionRepository.save(currentPublished);
        }

        draft.setStatus(EntityStatus.PUBLISHED);
        draft.setDraftOwnerId(null);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(actorId);
        KnowledgeProviderVersion saved = versionRepository.save(draft);

        provider.setCurrentVersionId(saved.getId());
        provider.setStatus(EntityStatus.ACTIVE);
        provider.setPublishedAt(Instant.now());
        provider.setPublishedBy(actorId);
        repository.save(provider);

        log.info("Published knowledge provider version {} (id: {})", draft.getVersionNumber(), providerId);

        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, EntityStatus.PUBLISHED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null, Map.of("version_number", draft.getVersionNumber()), null);

        return saved;
    }

    @SuppressWarnings("unchecked")
    private void validateResponseMappingField(Map<String, Object> responseMapping, String field) {
        String value = (String) responseMapping.get(field);
        if (value == null || value.isBlank()) {
            throw BadRequestException.forField("responseMapping." + field, "REQUIRED",
                    "Response mapping field '" + field + "' is required before publishing.");
        }
    }

    @Transactional
    public void discardDraft(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        KnowledgeProviderVersion draft = getDraftVersion(providerId);
        if (draft == null) {
            throw new BadRequestException("No draft version exists to discard.");
        }
        versionRepository.delete(draft);
        log.info("Discarded draft for knowledge provider: {}", providerId);

        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, AuditAction.DRAFT_DISCARDED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                draft.getId(), null, null, null, null);
    }

    @Transactional
    public KnowledgeProvider disable(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        provider.setStatus(EntityStatus.DISABLED);
        KnowledgeProvider saved = repository.save(provider);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, EntityStatus.DISABLED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_DISABLED, null, null, null);
        return saved;
    }

    @Transactional
    public KnowledgeProvider reenable(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        provider.setStatus(EntityStatus.ACTIVE);
        KnowledgeProvider saved = repository.save(provider);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, AuditAction.REENABLED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_REENABLED, null, null, null);
        return saved;
    }

    // ---- delete -------------------------------------------------------

    @Transactional
    public void delete(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        // Delete project provider bindings (FK → knowledge_providers)
        bindingRepository.findByProviderId(providerId).forEach(bindingRepository::delete);
        // Delete linked data feeds, sources (FK → versions), then versions, then parent
        List<KnowledgeProviderVersion> versions = versionRepository.findAllByProviderIdOrderByVersionNumberDesc(providerId);
        for (KnowledgeProviderVersion v : versions) {
            dataFeedRepository.findByProviderVersionId(v.getId()).forEach(dataFeedRepository::delete);
            sourceRepository.findByProviderVersionId(v.getId()).forEach(sourceRepository::delete);
        }
        versionRepository.deleteAll(versions);
        repository.delete(provider);
        log.info("Deleted knowledge provider: {} (id: {})", provider.getName(), providerId);
    }

    // ---- Zone 1 update ------------------------------------------------

    @Transactional
    public KnowledgeProvider updateZone1(UUID providerId, String name, String description,
                                          UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        if (name != null && !name.isBlank()) provider.setName(name);
        if (description != null) provider.setDescription(description);
        provider.setUpdatedBy(actorId);
        KnowledgeProvider saved = repository.save(provider);
        log.info("Updated knowledge provider Zone 1: {}", providerId);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, AuditAction.UPDATED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, null, null, Map.of(), null);
        return saved;
    }

    // ---- update draft -------------------------------------------------

    @Transactional
    public KnowledgeProviderVersion updateDraft(UUID providerId, UUID connectionConfigId,
                                                 Map<String, Object> config,
                                                 UUID actorId, String actorDisplayName) {
        KnowledgeProviderVersion draft = getDraftVersion(providerId);
        if (draft == null) {
            throw new BadRequestException("No draft version exists to update.");
        }
        if (connectionConfigId != null) draft.setConnectionConfigId(connectionConfigId);
        if (config != null) draft.setConfig(config);
        return versionRepository.save(draft);
    }

    // ---- archive / unarchive ------------------------------------------

    @Transactional
    public KnowledgeProvider archive(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        if (!EntityStatus.DISABLED.equals(provider.getStatus())) {
            throw new BadRequestException("Only DISABLED providers can be archived.");
        }
        provider.setStatus(EntityStatus.ARCHIVED);
        KnowledgeProvider saved = repository.save(provider);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, EntityStatus.ARCHIVED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_ARCHIVED, null, null, null);
        return saved;
    }

    @Transactional
    public KnowledgeProvider unarchive(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        if (!EntityStatus.ARCHIVED.equals(provider.getStatus())) {
            throw new BadRequestException("Only ARCHIVED providers can be un-archived.");
        }
        provider.setStatus(EntityStatus.DISABLED);
        KnowledgeProvider saved = repository.save(provider);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, AuditAction.UNARCHIVED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_UNARCHIVED, null, null, null);
        return saved;
    }

    // ---- version history ----------------------------------------------

    @Transactional(readOnly = true)
    public List<KnowledgeProviderVersion> getVersionHistory(UUID providerId) {
        findById(providerId); // ensure exists
        List<KnowledgeProviderVersion> result = new ArrayList<>();
        result.addAll(versionRepository.findAllByProviderIdOrderByVersionNumberDesc(providerId));
        return result;
    }

    // ---- clone archived version as draft -------------------------------

    @Transactional
    public KnowledgeProviderVersion cloneVersion(UUID providerId, UUID versionId,
                                                   UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        KnowledgeProviderVersion existingDraft = getDraftVersion(providerId);
        if (existingDraft != null) {
            throw new BadRequestException("A draft version already exists. Discard it before cloning.");
        }

        KnowledgeProviderVersion source = versionRepository.findById(versionId)
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeProviderVersion", versionId));

        // Find max version number
        List<KnowledgeProviderVersion> all = versionRepository.findAllByProviderIdOrderByVersionNumberDesc(providerId);
        int maxVersion = all.isEmpty() ? 0 : all.get(0).getVersionNumber();

        KnowledgeProviderVersion draft = new KnowledgeProviderVersion();
        draft.setProviderId(providerId);
        draft.setVersionNumber(maxVersion + 1);
        draft.setParentVersionId(source.getId());
        draft.setStatus(EntityStatus.DRAFT);
        draft.setConnectionConfigId(source.getConnectionConfigId());
        draft.setConfig(source.getConfig());
        draft.setDraftOwnerId(actorId);
        KnowledgeProviderVersion saved = versionRepository.save(draft);

        log.info("Cloned version {} as draft v{} for provider {}", versionId, saved.getVersionNumber(), providerId);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_PROVIDER, providerId, AuditAction.VERSION_CLONED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null,
                Map.of("source_version_id", versionId, "new_version_number", saved.getVersionNumber()), null);

        return saved;
    }

    // ---- knowledge source CRUD ----------------------------------------

    @Transactional(readOnly = true)
    public List<KnowledgeSource> getKnowledgeSources(UUID providerId) {
        findById(providerId); // ensure exists
        // Return sources linked to Draft if exists, else Published
        KnowledgeProviderVersion draft = getDraftVersion(providerId);
        if (draft != null) {
            return sourceRepository.findByProviderVersionId(draft.getId());
        }
        KnowledgeProviderVersion published = versionRepository
                .findByProviderIdAndStatus(providerId, EntityStatus.PUBLISHED).orElse(null);
        if (published != null) {
            return sourceRepository.findByProviderVersionId(published.getId());
        }
        return List.of();
    }

    @Transactional(readOnly = true)
    public int countKnowledgeSources(UUID providerId) {
        return getKnowledgeSources(providerId).size();
    }

    @Transactional
    public KnowledgeSource addKnowledgeSource(UUID providerId, String name, String description,
                                                Map<String, Object> config,
                                                UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        KnowledgeProviderVersion draft = getDraftVersion(providerId);
        if (draft == null) {
            throw new BadRequestException("Create a new Provider version to add knowledge sources.");
        }

        // Check name uniqueness within this version
        List<KnowledgeSource> existing = sourceRepository.findByProviderVersionId(draft.getId());
        if (existing.stream().anyMatch(s -> s.getName().equals(name))) {
            throw BadRequestException.forField("name", "DUPLICATE_CODE",
                    "A knowledge source with this name already exists for this provider.");
        }

        KnowledgeSource source = new KnowledgeSource();
        source.setScope(provider.getScope());
        source.setProjectId(provider.getProjectId());
        source.setName(name);
        source.setDescription(description);
        source.setStatus(EntityStatus.ACTIVE);
        source.setProviderVersionId(draft.getId());
        source.setConfig(config);
        source.setAvailability("GLOBAL");
        source.setPriority(0);
        source.setCreatedBy(actorId);

        KnowledgeSource saved = sourceRepository.save(source);
        log.info("Added knowledge source '{}' to provider {} draft", name, providerId);

        auditEventService.recordEvent(ResourceType.KNOWLEDGE_SOURCE, saved.getId(), AuditAction.CREATED,
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, null, null, Map.of("name", name, "providerId", providerId), null);

        return saved;
    }

    @Transactional
    public KnowledgeSource updateKnowledgeSource(UUID sourceId, String name, String description,
                                                   Map<String, Object> config,
                                                   UUID actorId, String actorDisplayName) {
        KnowledgeSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeSource", sourceId));
        if (name != null && !name.isBlank()) source.setName(name);
        if (description != null) source.setDescription(description);
        if (config != null) source.setConfig(config);
        source.setUpdatedBy(actorId);
        return sourceRepository.save(source);
    }

    @Transactional
    public void deleteKnowledgeSource(UUID sourceId, UUID actorId, String actorDisplayName) {
        KnowledgeSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeSource", sourceId));
        sourceRepository.delete(source);
        log.info("Deleted knowledge source: {} (id: {})", source.getName(), sourceId);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_SOURCE, sourceId, AuditAction.DELETED,
                source.getScope(), source.getProjectId(), actorId, actorDisplayName,
                null, null, null, Map.of("name", source.getName()), null);
    }
}