// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Builds the chunk context payload for the UI side panel.
 */
@Service
@RequiredArgsConstructor
public class ChunkContextService {

    private static final int NEIGHBOUR_WINDOW = 2;

    private final KnowledgeChunkRepository chunkRepository;

    @Transactional(readOnly = true)
    public ChunkContext getContext(UUID chunkId, UUID knowledgeSourceId) {
        KnowledgeChunk anchor = chunkRepository.findById(chunkId)
                .orElseThrow(() -> ResourceNotFoundException.knowledgeChunk(chunkId));

        if (!knowledgeSourceId.equals(anchor.getKnowledgeSourceId())) {
            throw ResourceNotFoundException.knowledgeChunk(chunkId);
        }

        List<KnowledgeChunk> neighbours = List.of();
        if (anchor.getSequenceNo() != null) {
            long start = Math.max(0, anchor.getSequenceNo() - NEIGHBOUR_WINDOW);
            long end = anchor.getSequenceNo() + NEIGHBOUR_WINDOW;
            neighbours = chunkRepository.findByKnowledgeSourceIdAndSequenceNoBetween(
                    knowledgeSourceId, start, end);
        }

        return new ChunkContext(anchor, neighbours);
    }
}