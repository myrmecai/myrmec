// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.knowledge.dto.CreateKnowledgeSourceRequest;
import ai.myrmec.engine.knowledge.dto.KnowledgeSourceResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Knowledge Sources", description = "Datasets within a provider version for retrieval")
public class KnowledgeSourceController {

    private final KnowledgeSourceService service;

    @GetMapping("/api/v1/admin/knowledge-sources")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List all org-scoped knowledge sources")
    public ResponseEntity<List<KnowledgeSourceResponse>> list() {
        return ResponseEntity.ok(service.findAllOrgScoped().stream()
                .map(KnowledgeSourceResponse::from).toList());
    }

    @GetMapping("/api/v1/admin/knowledge-sources/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get a knowledge source by id")
    public ResponseEntity<KnowledgeSourceResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(KnowledgeSourceResponse.from(service.findById(id)));
    }

    @PostMapping("/api/v1/admin/knowledge-sources")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a knowledge source (deprecated — use POST /knowledge-providers/{id}/knowledge-sources)")
    public ResponseEntity<KnowledgeSourceResponse> create(
            @Valid @RequestBody CreateKnowledgeSourceRequest request,
            @CurrentUser UUID userId) {
        // Legacy endpoint — no longer supports standalone creation without a provider
        throw new BadRequestException("Use POST /api/v1/admin/knowledge-providers/{id}/knowledge-sources to create a knowledge source.");
    }

    @PostMapping("/api/v1/admin/knowledge-sources/{id}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Disable a knowledge source")
    public ResponseEntity<KnowledgeSourceResponse> disable(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeSourceResponse.from(
                service.disable(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }

    @PostMapping("/api/v1/admin/knowledge-sources/{id}/archive")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Archive a knowledge source")
    public ResponseEntity<KnowledgeSourceResponse> archive(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeSourceResponse.from(
                service.archive(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }
}