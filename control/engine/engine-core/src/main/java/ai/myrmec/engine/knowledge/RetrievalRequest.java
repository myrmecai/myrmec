// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/agent/sessions/{sessionId}/retrieve}.
 *
 * <p>The session is in the path; the body carries the RAG query. The engine
 * validates the {@code sourceId} against the session's pinned context before
 * dispatching to the {@link ai.myrmec.engine.spi.retrieval.RetrievalProvider}.</p>
 *
 * @param sourceId   non-null knowledge source UUID (must be pinned to the
 *                   session's context)
 * @param query      non-blank natural-language query
 * @param topK       max hits (default 5)
 * @param filters    optional provider-specific filters
 * @param taskId     optional workflow task ID for audit context
 * @param attemptId  optional attempt ID for audit context
 */
public record RetrievalRequest(
    @NotNull UUID sourceId,
    @NotBlank String query,
    @Min(1) Integer topK,
    Map<String, String> filters,
    UUID taskId,
    UUID attemptId
) {}