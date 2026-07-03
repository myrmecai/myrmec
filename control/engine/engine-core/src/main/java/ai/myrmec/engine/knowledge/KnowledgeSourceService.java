// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

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
    private final AuditEventService auditEventService;

    @Transactional(readOnly = true)
    public List<KnowledgeSource> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull("ORGANIZATION");
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
        source.setStatus("ACTIVE");
        source.setProviderVersionId(providerVersionId);
        source.setConfig(config);
        source.setAvailability(availability != null ? availability : "GLOBAL");
        source.setPriority(priority != null ? priority : 0);
        source.setCreatedBy(actorId);

        KnowledgeSource saved = repository.save(source);
        log.info("Created knowledge source: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent("knowledge_source", saved.getId(), "CREATED",
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name), null);

        return saved;
    }

    @Transactional
    public KnowledgeSource disable(UUID id, UUID actorId, String actorDisplayName) {
        KnowledgeSource source = findById(id);
        source.setStatus("DISABLED");
        KnowledgeSource saved = repository.save(source);
        auditEventService.recordEvent("knowledge_source", id, "DISABLED",
                source.getScope(), source.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_DISABLED", null, null, null);
        return saved;
    }

    @Transactional
    public KnowledgeSource archive(UUID id, UUID actorId, String actorDisplayName) {
        KnowledgeSource source = findById(id);
        source.setStatus("ARCHIVED");
        KnowledgeSource saved = repository.save(source);
        auditEventService.recordEvent("knowledge_source", id, "ARCHIVED",
                source.getScope(), source.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_ARCHIVED", null, null, null);
        return saved;
    }
}