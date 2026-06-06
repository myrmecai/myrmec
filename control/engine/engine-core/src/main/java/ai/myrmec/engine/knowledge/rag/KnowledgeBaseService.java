package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.spi.retrieval.KnowledgeBaseScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRUD service for {@link KnowledgeBase} and {@link KnowledgeSource}.
 *
 * <p>Enforces the scope ↔ owner consistency that the database CHECK
 * constraint also enforces (validating at the service layer surfaces
 * better error messages). ACL checks are the responsibility of the
 * calling controller — this service trusts its inputs.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeSourceRepository knowledgeSourceRepository;

    public KnowledgeBase createSystemBase(String name, String description, String providerId, String providerConfig) {
        Optional<KnowledgeBase> existing = knowledgeBaseRepository
                .findByScopeAndProjectIdIsNullAndGroupIdIsNullAndName(KnowledgeBaseScope.SYSTEM, name);
        if (existing.isPresent()) {
            throw new DuplicateResourceException("KnowledgeBase", "name", "SYSTEM/" + name);
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setScope(KnowledgeBaseScope.SYSTEM);
        kb.setProviderId(providerId);
        kb.setProviderConfig(providerConfig);
        return knowledgeBaseRepository.save(kb);
    }

    public KnowledgeBase createGroupBase(UUID groupId, String name, String description, String providerId, String providerConfig) {
        if (groupId == null) {
            throw new BadRequestException("groupId is required for GROUP-scoped knowledge bases");
        }
        if (knowledgeBaseRepository.findByScopeAndGroupIdAndName(KnowledgeBaseScope.GROUP, groupId, name).isPresent()) {
            throw new DuplicateResourceException("KnowledgeBase", "name", "GROUP/" + groupId + "/" + name);
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setScope(KnowledgeBaseScope.GROUP);
        kb.setGroupId(groupId);
        kb.setProviderId(providerId);
        kb.setProviderConfig(providerConfig);
        return knowledgeBaseRepository.save(kb);
    }

    public KnowledgeBase createProjectBase(UUID projectId, String name, String description, String providerId, String providerConfig) {
        if (projectId == null) {
            throw new BadRequestException("projectId is required for PROJECT-scoped knowledge bases");
        }
        if (knowledgeBaseRepository.findByScopeAndProjectIdAndName(KnowledgeBaseScope.PROJECT, projectId, name).isPresent()) {
            throw new DuplicateResourceException("KnowledgeBase", "name", "PROJECT/" + projectId + "/" + name);
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setScope(KnowledgeBaseScope.PROJECT);
        kb.setProjectId(projectId);
        kb.setProviderId(providerId);
        kb.setProviderConfig(providerConfig);
        return knowledgeBaseRepository.save(kb);
    }

    public KnowledgeSource addSource(UUID knowledgeBaseId,
                                     String connectorType,
                                     String name,
                                     String uri,
                                     String configJson,
                                     String syncSchedule) {
        if (knowledgeBaseRepository.findById(knowledgeBaseId).isEmpty()) {
            throw new ResourceNotFoundException("KnowledgeBase", knowledgeBaseId);
        }
        if (knowledgeSourceRepository.findByKnowledgeBaseIdAndName(knowledgeBaseId, name).isPresent()) {
            throw new DuplicateResourceException("KnowledgeSource", "name", knowledgeBaseId + "/" + name);
        }
        KnowledgeSource source = new KnowledgeSource();
        source.setKnowledgeBaseId(knowledgeBaseId);
        source.setConnectorType(connectorType);
        source.setName(name);
        source.setUri(uri);
        source.setConfigJson(configJson);
        source.setSyncSchedule(syncSchedule);
        return knowledgeSourceRepository.save(source);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBase> listProjectBases(UUID projectId) {
        return knowledgeBaseRepository.findByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBase> listGroupBases(UUID groupId) {
        return knowledgeBaseRepository.findByGroupId(groupId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBase> listSystemBases() {
        return knowledgeBaseRepository.findByScope(KnowledgeBaseScope.SYSTEM);
    }
}
