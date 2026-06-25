// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.knowledge.rag.dto.ChunkContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * User-facing chunk-context preview for the #30 citation side panel.
 *
 * <p>Given a citation's {@code chunkId}, returns the cited passage plus its
 * neighbouring passages so the chat UI can render a source preview. Access
 * mirrors the other project knowledge-base reads: {@code VIEWER} on the project
 * (gated by {@code @projectAccess}), and the chunk's KB is re-validated against
 * {@code projectId} in the service so a caller cannot reach another project's
 * (or another KB's) chunks by id — cross-scope reads return 404 with no
 * existence leak.</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge-bases/{kbId}/chunks")
@RequiredArgsConstructor
public class ChunkContextController {

    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/{chunkId}/context")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public ChunkContextResponse getContext(
            @PathVariable UUID projectId,
            @PathVariable UUID kbId,
            @PathVariable UUID chunkId) {
        return knowledgeBaseService.getProjectChunkContext(projectId, kbId, chunkId);
    }
}