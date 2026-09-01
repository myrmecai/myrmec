// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link KnowledgeChunk} rows.
 */
@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, UUID> {

    /**
     * Load the chunks surrounding a given sequence number within a source.
     * Used by the chunk context side panel.
     */
    List<KnowledgeChunk> findByKnowledgeSourceIdAndSequenceNoBetween(
            UUID knowledgeSourceId, Long start, Long end);
}