// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.security.AgentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controller for the agent retrieval endpoint (§8).
 *
 * <p>Executes a knowledge-source retrieval query engine-side. The agent
 * calls this via {@code ctx.retrieve()} — it never receives provider
 * endpoints, auth, or config.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Agent Retrieval", description = "Knowledge-source retrieval for authenticated agents")
public class AgentRetrievalController {

    private final AgentRetrievalService retrievalService;

    @Operation(
            summary = "Retrieve knowledge chunks",
            description = "Execute a retrieval query against a knowledge source. The source must be "
                    + "pinned to the agent's active session. Returns wrapped passages with citations."
    )
    @PostMapping("/retrieve")
    public ResponseEntity<List<RetrievalHit>> retrieve(
            @AuthenticationPrincipal AgentPrincipal principal,
            @Valid @RequestBody RetrievalRequest request) {

        List<RetrievalHit> hits = retrievalService.retrieve(
                principal.getInstanceId(),
                request);
        return ResponseEntity.ok(hits);
    }
}