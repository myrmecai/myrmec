// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._controllers.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.myrmec.engine.knowledge.ChunkContext;
import ai.myrmec.engine.knowledge.ChunkContextService;

import java.util.UUID;

/**
 * UI endpoint for the chunk context side panel.
 *
 * <p>Exposes {@code GET /api/v1/teams/{teamId}/knowledge-bases/{kbId}/chunks/{chunkId}/context}
 * so the UI can show the passage and its neighbours for a citation.</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/knowledge-bases/{kbId}")
@RequiredArgsConstructor
public class ChunkContextController {

    private final ChunkContextService chunkContextService;

    @GetMapping("/chunks/{chunkId}/context")
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    public ResponseEntity<ChunkContext> getContext(
            @PathVariable UUID teamId,
            @PathVariable UUID kbId,
            @PathVariable UUID chunkId) {
        return ResponseEntity.ok(chunkContextService.getContext(chunkId, kbId));
    }
}