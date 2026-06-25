// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag.dto;

import java.util.List;
import java.util.UUID;

/**
 * User-facing chunk-context preview returned by
 * {@code GET /api/v1/projects/{projectId}/knowledge-bases/{kbId}/chunks/{chunkId}/context}
 * (#30 citation side panel).
 *
 * <p>Unlike the agent retrieval path, the passage here is shown directly to a
 * user who is already entitled to the source, so it is <strong>not</strong>
 * wrapped in an {@code <untrusted>} envelope (that guard only protects model
 * context). {@link #before} and {@link #after} carry the neighbouring chunk
 * passages (document order) so the UI can render surrounding context; either
 * may be empty.</p>
 */
public record ChunkContextResponse(
    UUID chunkId,
    UUID sourceId,
    String sourceName,
    String locator,
    String passage,
    List<String> before,
    List<String> after
) {
}