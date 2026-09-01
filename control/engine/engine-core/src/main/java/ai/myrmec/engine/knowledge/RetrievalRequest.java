// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/agent/retrieve} (§8).
 *
 * <p>The agent calls this endpoint to execute a knowledge-source retrieval
 * query. The engine validates the {@code knowledgeSourceId} against the
 * calling session's pinned context before dispatching to the
 * {@link ai.myrmec.engine.spi.retrieval.RetrievalProvider}.</p>
 *
 * @param knowledgeSourceId non-null knowledge source UUID (must be pinned
 *                          to the agent's active session)
 * @param query             non-blank natural-language query
 * @param topK              max hits (default 5)
 * @param filters           optional provider-specific filters
 * @param sessionId         optional session UUID — when provided, the
 *                          engine validates the source is pinned to this
 *                          session; when omitted, the engine resolves the
 *                          active session from the agent instance
 * @param taskId            optional workflow task ID for audit context
 * @param attemptId         optional attempt ID for audit context
 */
public record RetrievalRequest(
    @NotNull UUID knowledgeSourceId,
    @NotBlank String query,
    @Min(1) Integer topK,
    Map<String, String> filters,
    UUID sessionId,
    UUID taskId,
    UUID attemptId
) {}