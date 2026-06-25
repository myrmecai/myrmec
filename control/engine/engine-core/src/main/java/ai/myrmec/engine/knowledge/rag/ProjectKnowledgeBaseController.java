package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.knowledge.rag.dto.CreateKnowledgeBaseRequest;
import ai.myrmec.engine.knowledge.rag.dto.CreateKnowledgeSourceRequest;
import ai.myrmec.engine.knowledge.rag.dto.KnowledgeBaseResponse;
import ai.myrmec.engine.knowledge.rag.dto.KnowledgeSourceResponse;
import ai.myrmec.engine.knowledge.rag.dto.SyncResultResponse;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.user.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Project-scoped knowledge-base + source management.
 *
 * <p>Read endpoints require {@code VIEWER}; mutating endpoints (including the
 * manual {@code sync} trigger) require {@code EDITOR} on the project. Every
 * {@code kbId}/{@code sourceId} is re-validated against {@code projectId} in the
 * service layer so a caller authorized on one project cannot reach another
 * project's knowledge bases by id.</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge-bases")
@RequiredArgsConstructor
public class ProjectKnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ConnectorDispatcher connectorDispatcher;
    private final RetrievalDispatcher retrievalDispatcher;

    // ==================== Knowledge bases ====================

    @GetMapping
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public List<KnowledgeBaseResponse> list(@PathVariable UUID projectId) {
        return knowledgeBaseService.listProjectBases(projectId).stream()
                .map(this::toResponse)
                .toList();
    }

    @GetMapping("/{kbId}")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public KnowledgeBaseResponse get(@PathVariable UUID projectId, @PathVariable UUID kbId) {
        return toResponse(knowledgeBaseService.getProjectBase(projectId, kbId));
    }

    @PostMapping
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<KnowledgeBaseResponse> create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateKnowledgeBaseRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        String providerId = (request.providerId() == null || request.providerId().isBlank())
                ? StubRetrievalProvider.PROVIDER_ID
                : request.providerId().trim();
        if (!retrievalDispatcher.providerIds().contains(providerId)) {
            throw new BadRequestException("Unknown retrieval provider: " + providerId);
        }
        KnowledgeBase kb = knowledgeBaseService.createProjectBase(
                projectId,
                request.name(),
                request.description(),
                providerId,
                null,
                principal.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(kb));
    }

    @DeleteMapping("/{kbId}")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID kbId) {
        knowledgeBaseService.deleteProjectBase(projectId, kbId);
        return ResponseEntity.noContent().build();
    }

    // ==================== Sources ====================

    @GetMapping("/{kbId}/sources")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public List<KnowledgeSourceResponse> listSources(@PathVariable UUID projectId, @PathVariable UUID kbId) {
        knowledgeBaseService.getProjectBase(projectId, kbId);
        return knowledgeBaseService.listSources(kbId).stream()
                .map(this::toResponse)
                .toList();
    }

    @PostMapping("/{kbId}/sources")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<KnowledgeSourceResponse> addSource(
            @PathVariable UUID projectId,
            @PathVariable UUID kbId,
            @Valid @RequestBody CreateKnowledgeSourceRequest request) {
        knowledgeBaseService.getProjectBase(projectId, kbId);
        String connectorType = request.connectorType().trim();
        if (!connectorDispatcher.connectorTypes().contains(connectorType)) {
            throw new BadRequestException("Unknown connector type: " + connectorType);
        }
        KnowledgeSource source = knowledgeBaseService.addSource(
                kbId,
                connectorType,
                request.name(),
                request.uri(),
                request.configJson(),
                request.syncSchedule());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(source));
    }

    @DeleteMapping("/{kbId}/sources/{sourceId}")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public ResponseEntity<Void> deleteSource(
            @PathVariable UUID projectId,
            @PathVariable UUID kbId,
            @PathVariable UUID sourceId) {
        knowledgeBaseService.getProjectSource(projectId, kbId, sourceId);
        knowledgeBaseService.deleteSource(sourceId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Trigger a manual sync of a single source. Returns 200 with the sync
     * outcome; a connector failure is surfaced as {@code status=FAILED} rather
     * than an HTTP error so the UI can render the message inline.
     */
    @PostMapping("/{kbId}/sources/{sourceId}/sync")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication)")
    public SyncResultResponse sync(
            @PathVariable UUID projectId,
            @PathVariable UUID kbId,
            @PathVariable UUID sourceId) {
        knowledgeBaseService.getProjectSource(projectId, kbId, sourceId);
        try {
            return SyncResultResponse.from(connectorDispatcher.sync(sourceId));
        } catch (ConnectorException e) {
            return SyncResultResponse.failed(e.getMessage());
        }
    }

    // ==================== Mapping ====================

    private KnowledgeBaseResponse toResponse(KnowledgeBase kb) {
        long sourceCount = knowledgeBaseService.listSources(kb.getId()).size();
        return KnowledgeBaseResponse.from(kb, sourceCount);
    }

    private KnowledgeSourceResponse toResponse(KnowledgeSource source) {
        return KnowledgeSourceResponse.from(source, knowledgeBaseService.countChunks(source.getId()));
    }
}
