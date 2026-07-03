// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeProviderService {

    private final KnowledgeProviderRepository repository;
    private final KnowledgeProviderVersionRepository versionRepository;
    private final AuditEventService auditEventService;

    @Transactional(readOnly = true)
    public List<KnowledgeProvider> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull("ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public KnowledgeProvider findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeProvider", id));
    }

    @Transactional(readOnly = true)
    public KnowledgeProviderVersion getPublishedVersion(UUID providerId) {
        return versionRepository.findByProviderIdAndStatus(providerId, "PUBLISHED")
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeProviderVersion",
                        "providerId=" + providerId + ", status=PUBLISHED"));
    }

    @Transactional(readOnly = true)
    public KnowledgeProviderVersion getDraftVersion(UUID providerId) {
        return versionRepository.findByProviderIdAndStatus(providerId, "DRAFT").orElse(null);
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

        KnowledgeProvider provider = new KnowledgeProvider();
        provider.setScope(scope);
        provider.setProjectId(projectId);
        provider.setName(name);
        provider.setDescription(description);
        provider.setType(type);
        provider.setStatus("INCOMPLETE");
        provider.setCreatedBy(actorId);

        KnowledgeProvider saved = repository.save(provider);
        log.info("Created knowledge provider: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent("knowledge_provider", saved.getId(), "CREATED",
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
                .findByProviderIdAndStatus(providerId, "PUBLISHED").orElse(null);

        int versionNumber = publishedVersion != null ? publishedVersion.getVersionNumber() + 1 : 1;

        KnowledgeProviderVersion draft = new KnowledgeProviderVersion();
        draft.setProviderId(providerId);
        draft.setVersionNumber(versionNumber);
        draft.setParentVersionId(publishedVersion != null ? publishedVersion.getId() : null);
        draft.setStatus("DRAFT");
        draft.setConnectionConfigId(connectionConfigId);
        draft.setConfig(config);
        draft.setDraftOwnerId(actorId);

        KnowledgeProviderVersion saved = versionRepository.save(draft);
        log.info("Created draft version {} for knowledge provider: {}", versionNumber, providerId);

        auditEventService.recordEvent("knowledge_provider", providerId, "DRAFT_CREATED",
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

        KnowledgeProviderVersion currentPublished = versionRepository
                .findByProviderIdAndStatus(providerId, "PUBLISHED").orElse(null);
        if (currentPublished != null) {
            currentPublished.setStatus("ARCHIVED");
            versionRepository.save(currentPublished);
        }

        draft.setStatus("PUBLISHED");
        draft.setDraftOwnerId(null);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(actorId);
        KnowledgeProviderVersion saved = versionRepository.save(draft);

        provider.setCurrentVersionId(saved.getId());
        provider.setStatus("ACTIVE");
        provider.setPublishedAt(Instant.now());
        provider.setPublishedBy(actorId);
        repository.save(provider);

        log.info("Published knowledge provider version {} (id: {})", draft.getVersionNumber(), providerId);

        auditEventService.recordEvent("knowledge_provider", providerId, "PUBLISHED",
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null, Map.of("version_number", draft.getVersionNumber()), null);

        return saved;
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

        auditEventService.recordEvent("knowledge_provider", providerId, "DRAFT_DISCARDED",
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                draft.getId(), null, null, null, null);
    }

    @Transactional
    public KnowledgeProvider disable(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        provider.setStatus("DISABLED");
        KnowledgeProvider saved = repository.save(provider);
        auditEventService.recordEvent("knowledge_provider", providerId, "DISABLED",
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_DISABLED", null, null, null);
        return saved;
    }

    @Transactional
    public KnowledgeProvider reenable(UUID providerId, UUID actorId, String actorDisplayName) {
        KnowledgeProvider provider = findById(providerId);
        provider.setStatus("ACTIVE");
        KnowledgeProvider saved = repository.save(provider);
        auditEventService.recordEvent("knowledge_provider", providerId, "REENABLED",
                provider.getScope(), provider.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_REENABLED", null, null, null);
        return saved;
    }
}