// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import java.util.List;
import java.util.UUID;

/**
 * Response body for {@code POST /api/v1/agent/retrieve} (§8).
 *
 * <p>A single retrieval hit. The list of these is returned to the agent.
 * Passage text is wrapped in {@code <untrusted>} envelopes by the engine
 * before it reaches the agent.</p>
 *
 * @param passage    wrapped chunk content
 * @param chunkId     knowledge_chunks row UUID
 * @param sourceId    knowledge_sources row UUID
 * @param sourceName  human-readable source name
 * @param locator     deep-link / file path
 * @param score       similarity score [0.0, 1.0]
 */
public record RetrievalHit(
    String passage,
    UUID chunkId,
    UUID sourceId,
    String sourceName,
    String locator,
    double score
) {}