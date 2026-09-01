// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import java.util.List;

/**
 * Chunk context response: the anchor chunk plus surrounding neighbours.
 *
 * @param anchor     the chunk the user clicked on
 * @param neighbours chunks surrounding the anchor (may be empty)
 */
public record ChunkContext(
        KnowledgeChunk anchor,
        List<KnowledgeChunk> neighbours) {
}