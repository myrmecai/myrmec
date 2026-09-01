// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSourceService {

    private final KnowledgeSourceRepository repository;
    private final KnowledgeProviderRepository providerRepository;
    private final KnowledgeProviderVersionRepository providerVersionRepository;
    private final AuditEventService auditEventService;

    /**
     * List org-scoped knowledge sources.
     *
     * <p>By default ({@code includeArchived=false}) sources belonging to an
     * ARCHIVED provider are excluded — an archived provider is decommissioned,
     * so its sources are neither usable nor manageable. Set
     * {@code includeArchived=true} to see them (e.g. for audit views).</p>
     */
    @Transactional(readOnly = true)
    public List<KnowledgeSource> findAllOrgScoped(boolean includeArchived) {
        List<KnowledgeSource> sources = repository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION);
        if (includeArchived) {
            return sources;
        }
        // Collect the ids of archived providers once, then filter.
        var archivedProviderIds = providerRepository.findAll().stream()
                .filter(p -> EntityStatus.ARCHIVED.equals(p.getStatus()))
                .map(KnowledgeProvider::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (archivedProviderIds.isEmpty()) {
            return sources;
        }
        // Map provider version ids → provider ids to filter by parent provider.
        return sources.stream()
                .filter(s -> {
                    var version = providerVersionRepository.findById(s.getProviderVersionId()).orElse(null);
                    return version == null || !archivedProviderIds.contains(version.getProviderId());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeSource findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("KnowledgeSource", id));
    }

    @Transactional
    public KnowledgeSource create(String scope, UUID projectId, String name, String description,
                                   UUID providerVersionId, Map<String, Object> config,
                                   String availability, Integer priority,
                                   UUID actorId, String actorDisplayName) {
        KnowledgeSource source = new KnowledgeSource();
        source.setScope(scope);
        source.setProjectId(projectId);
        source.setName(name);
        source.setDescription(description);
        source.setStatus(EntityStatus.ACTIVE);
        source.setProviderVersionId(providerVersionId);
        source.setConfig(config);
        source.setAvailability(availability != null ? availability : "GLOBAL");
        source.setPriority(priority != null ? priority : 0);
        source.setCreatedBy(actorId);

        KnowledgeSource saved = repository.save(source);
        log.info("Created knowledge source: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent(ResourceType.KNOWLEDGE_SOURCE, saved.getId(), AuditAction.CREATED,
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name), null);

        return saved;
    }

    @Transactional
    public KnowledgeSource disable(UUID id, UUID actorId, String actorDisplayName) {
        KnowledgeSource source = findById(id);
        source.setStatus(EntityStatus.DISABLED);
        KnowledgeSource saved = repository.save(source);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_SOURCE, id, EntityStatus.DISABLED,
                source.getScope(), source.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_DISABLED, null, null, null);
        return saved;
    }

    @Transactional
    public KnowledgeSource archive(UUID id, UUID actorId, String actorDisplayName) {
        KnowledgeSource source = findById(id);
        source.setStatus(EntityStatus.ARCHIVED);
        KnowledgeSource saved = repository.save(source);
        auditEventService.recordEvent(ResourceType.KNOWLEDGE_SOURCE, id, EntityStatus.ARCHIVED,
                source.getScope(), source.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_ARCHIVED, null, null, null);
        return saved;
    }
}