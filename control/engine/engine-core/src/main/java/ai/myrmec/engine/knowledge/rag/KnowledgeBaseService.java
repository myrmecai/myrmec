package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.knowledge.rag.dto.ChunkContextResponse;
import ai.myrmec.engine.spi.retrieval.KnowledgeBaseScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
    private final KnowledgeChunkRepository knowledgeChunkRepository;

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
        return createProjectBase(projectId, name, description, providerId, providerConfig, null);
    }

    public KnowledgeBase createProjectBase(UUID projectId, String name, String description,
                                           String providerId, String providerConfig, UUID createdBy) {
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
        kb.setCreatedBy(createdBy);
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
        if (syncSchedule != null && !syncSchedule.isBlank() && !CronExpression.isValidExpression(syncSchedule.trim())) {
            throw new BadRequestException("Invalid sync schedule cron expression: " + syncSchedule);
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

    /**
     * Load a PROJECT-scoped knowledge base, verifying it belongs to
     * {@code projectId}. A KB owned by another project (or a non-PROJECT KB)
     * is reported as not-found so callers cannot probe across project
     * boundaries — the controller already gated {@code projectId} via
     * {@code @projectAccess}.
     */
    @Transactional(readOnly = true)
    public KnowledgeBase getProjectBase(UUID projectId, UUID knowledgeBaseId) {
        KnowledgeBase kb = knowledgeBaseRepository.findById(knowledgeBaseId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeBase", knowledgeBaseId));
        if (kb.getScope() != KnowledgeBaseScope.PROJECT || !projectId.equals(kb.getProjectId())) {
            throw new ResourceNotFoundException("KnowledgeBase", knowledgeBaseId);
        }
        return kb;
    }

    /** Delete a project KB and every source + chunk under it. */
    public void deleteProjectBase(UUID projectId, UUID knowledgeBaseId) {
        KnowledgeBase kb = getProjectBase(projectId, knowledgeBaseId);
        for (KnowledgeSource source : knowledgeSourceRepository.findByKnowledgeBaseId(kb.getId())) {
            knowledgeChunkRepository.deleteByKnowledgeSourceId(source.getId());
            knowledgeSourceRepository.delete(source);
        }
        knowledgeBaseRepository.delete(kb);
        log.info("Deleted knowledge base {} (project {})", knowledgeBaseId, projectId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSource> listSources(UUID knowledgeBaseId) {
        return knowledgeSourceRepository.findByKnowledgeBaseId(knowledgeBaseId);
    }

    /** Load a source, verifying it belongs to the given project KB. */
    @Transactional(readOnly = true)
    public KnowledgeSource getProjectSource(UUID projectId, UUID knowledgeBaseId, UUID sourceId) {
        getProjectBase(projectId, knowledgeBaseId);
        KnowledgeSource source = knowledgeSourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeSource", sourceId));
        if (!knowledgeBaseId.equals(source.getKnowledgeBaseId())) {
            throw new ResourceNotFoundException("KnowledgeSource", sourceId);
        }
        return source;
    }

    /** Delete a single source and its chunks. */
    public void deleteSource(UUID sourceId) {
        KnowledgeSource source = knowledgeSourceRepository.findById(sourceId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeSource", sourceId));
        knowledgeChunkRepository.deleteByKnowledgeSourceId(sourceId);
        knowledgeSourceRepository.delete(source);
        log.info("Deleted knowledge source {}", sourceId);
    }

    @Transactional(readOnly = true)
    public long countChunks(UUID sourceId) {
        return knowledgeChunkRepository.countByKnowledgeSourceId(sourceId);
    }

    /**
     * Number of neighbour passages returned on each side of a cited chunk by
     * {@link #getProjectChunkContext}. Kept small so the side-panel payload
     * stays bounded regardless of source size.
     */
    private static final int NEIGHBOUR_WINDOW = 2;

    /**
     * Load the user-facing context preview for a cited chunk (#30): the chunk's
     * own passage plus up to {@link #NEIGHBOUR_WINDOW} neighbouring passages on
     * each side, in document order.
     *
     * <p>The KB is re-validated against {@code projectId} (cross-scope reads
     * report not-found, never leaking existence), and the chunk must belong to a
     * source under that KB — a chunk from another KB is likewise reported as
     * not-found. The controller has already gated {@code projectId} via
     * {@code @projectAccess}.</p>
     */
    @Transactional(readOnly = true)
    public ChunkContextResponse getProjectChunkContext(UUID projectId, UUID knowledgeBaseId, UUID chunkId) {
        getProjectBase(projectId, knowledgeBaseId);
        KnowledgeChunk chunk = knowledgeChunkRepository.findById(chunkId)
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeChunk", chunkId));
        KnowledgeSource source = knowledgeSourceRepository.findById(chunk.getKnowledgeSourceId())
                .orElseThrow(() -> new ResourceNotFoundException("KnowledgeChunk", chunkId));
        if (!knowledgeBaseId.equals(source.getKnowledgeBaseId())) {
            // Chunk exists but belongs to a different KB — report not-found so a
            // caller cannot probe chunk membership across knowledge bases.
            throw new ResourceNotFoundException("KnowledgeChunk", chunkId);
        }

        List<KnowledgeChunk> ordered =
                knowledgeChunkRepository.findByKnowledgeSourceIdOrderByCreatedAtAscIdAsc(source.getId());
        int idx = 0;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(chunkId)) {
                idx = i;
                break;
            }
        }
        List<String> before = new ArrayList<>();
        for (int i = Math.max(0, idx - NEIGHBOUR_WINDOW); i < idx; i++) {
            before.add(ordered.get(i).getContent());
        }
        List<String> after = new ArrayList<>();
        for (int i = idx + 1; i < ordered.size() && i <= idx + NEIGHBOUR_WINDOW; i++) {
            after.add(ordered.get(i).getContent());
        }
        return new ChunkContextResponse(
                chunk.getId(),
                source.getId(),
                source.getName(),
                chunk.getLocator(),
                chunk.getContent(),
                before,
                after);
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
