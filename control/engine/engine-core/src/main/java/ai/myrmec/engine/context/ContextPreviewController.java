// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin endpoint for previewing the resolved context for a project without
 * requiring a conversation.  Used by the E2E spec and the UI Effective Context
 * Preview page.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/projects/{projectId}/context-preview")
@RequiredArgsConstructor
@Tag(name = "Context Preview", description = "Preview the resolved AI context for a project")
public class ContextPreviewController {

    private final ContextBuilder contextBuilder;
    private final ai.myrmec.engine.conversation.ConversationRepository conversationRepository;

    @GetMapping
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    @Operation(summary = "Preview the resolved context for a project and service type")
    public ResponseEntity<Map<String, Object>> preview(
            @PathVariable("projectId") UUID projectId,
            @RequestParam(defaultValue = "CONVERSATION") String serviceType,
            @RequestParam(required = false) String fileType,
            @RequestParam(required = false) UUID conversationId) {

        Map<String, Object> executionContext = fileType != null
                ? Map.of("fileType", fileType)
                : null;

        // If a conversationId is provided, load its pinned snapshot
        ContextSnapshot snapshot = null;
        if (conversationId != null) {
            snapshot = conversationRepository.findById(conversationId)
                    .map(ai.myrmec.engine.conversation.Conversation::getContextSnapshot)
                    .orElse(null);
        }

        AssembledContext ctx = contextBuilder.assemble(
                projectId, null, serviceType, conversationId, null, 0, executionContext, snapshot);

        // Build a serializable response map
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("governanceProfileCode", ctx.governanceProfileCode());
        response.put("contextPinning", ctx.contextPinning());
        response.put("totalTokens", ctx.totalTokens());
        response.put("budgetTokens", ctx.budgetTokens());
        response.put("truncated", ctx.truncated());
        response.put("contextOverflow", ctx.contextOverflow());

        // Included instructions
        List<Map<String, Object>> instructions = ctx.instructions().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("assetId", e.assetId().toString());
                    m.put("versionId", e.versionId().toString());
                    m.put("name", e.name());
                    m.put("scope", e.scope());
                    m.put("category", e.category());
                    m.put("sourceType", e.sourceType());
                    m.put("contentPreview", e.contentPreview());
                    m.put("gitCommit", e.gitCommit());
                    m.put("inlineVersion", e.inlineVersion());
                    m.put("priority", e.priority());
                    m.put("tokenCount", e.tokenCount());
                    return m;
                })
                .toList();
        response.put("instructions", instructions);

        // Knowledge sources
        List<Map<String, Object>> knowledgeSources = ctx.knowledgeSources().stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("sourceId", s.sourceId().toString());
                    m.put("sourceName", s.sourceName());
                    m.put("providerType", s.providerType());
                    m.put("datasetName", s.datasetName());
                    m.put("availability", s.availability());
                    return m;
                })
                .toList();
        response.put("knowledgeSources", knowledgeSources);

        return ResponseEntity.ok(response);
    }
}