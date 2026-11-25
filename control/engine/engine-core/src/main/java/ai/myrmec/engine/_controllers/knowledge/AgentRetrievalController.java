// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._controllers.knowledge;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.myrmec.engine.knowledge.AgentRetrievalService;
import ai.myrmec.engine.knowledge.RetrievalHit;
import ai.myrmec.engine.knowledge.RetrievalRequest;

import java.util.List;
import java.util.UUID;

/**
 * Session-scoped RAG retrieval endpoint (§8).
 *
 * <p>Executes a knowledge-source retrieval query engine-side for a live
 * session. The calling host authenticates with its HOST_JWT; the service
 * validates the source against the session's pinned context before dispatching
 * to the retrieval provider.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent/sessions/{sessionId}")
@RequiredArgsConstructor
@Tag(name = "Agent Retrieval", description = "Session-scoped knowledge-source retrieval")
public class AgentRetrievalController {

    private final AgentRetrievalService retrievalService;

    @Operation(
            summary = "Retrieve knowledge chunks for a session",
            description = "Execute a retrieval query against a knowledge source pinned to the session. "
                    + "Returns wrapped passages with citations."
    )
    @PostMapping("/retrieve")
    public ResponseEntity<List<RetrievalHit>> retrieve(
            @PathVariable UUID sessionId,
            @Valid @RequestBody RetrievalRequest request) {
        // Auth is enforced by the security chain (HOST_JWT → ROLE_AGENT_HOST);
        // the service enforces the session/source pin.
        List<RetrievalHit> hits = retrievalService.retrieve(sessionId, request);
        return ResponseEntity.ok(hits);
    }
}
