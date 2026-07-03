// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.knowledge.dto.CreateKnowledgeProviderRequest;
import ai.myrmec.engine.knowledge.dto.CreateProviderDraftRequest;
import ai.myrmec.engine.knowledge.dto.KnowledgeProviderResponse;
import ai.myrmec.engine.knowledge.dto.KnowledgeProviderVersionResponse;
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
@Tag(name = "Knowledge Providers", description = "RAG providers (MANAGED/EXTERNAL) with versioned configs")
public class KnowledgeProviderController {

    private final KnowledgeProviderService service;

    @GetMapping("/api/v1/admin/knowledge-providers")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List all org-scoped knowledge providers")
    public ResponseEntity<List<KnowledgeProviderResponse>> list() {
        return ResponseEntity.ok(service.findAllOrgScoped().stream()
                .map(KnowledgeProviderResponse::from).toList());
    }

    @GetMapping("/api/v1/admin/knowledge-providers/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get a knowledge provider by id")
    public ResponseEntity<KnowledgeProviderResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(KnowledgeProviderResponse.from(service.findById(id)));
    }

    @GetMapping("/api/v1/admin/knowledge-providers/{id}/published-version")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get the published version")
    public ResponseEntity<KnowledgeProviderVersionResponse> getPublishedVersion(@PathVariable UUID id) {
        return ResponseEntity.ok(KnowledgeProviderVersionResponse.from(service.getPublishedVersion(id)));
    }

    @GetMapping("/api/v1/admin/knowledge-providers/{id}/draft-version")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get the draft version (if any)")
    public ResponseEntity<KnowledgeProviderVersionResponse> getDraftVersion(@PathVariable UUID id) {
        var draft = service.getDraftVersion(id);
        if (draft == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(KnowledgeProviderVersionResponse.from(draft));
    }

    @PostMapping("/api/v1/admin/knowledge-providers")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a knowledge provider")
    public ResponseEntity<KnowledgeProviderResponse> create(
            @Valid @RequestBody CreateKnowledgeProviderRequest request,
            @CurrentUser UUID userId) {
        var provider = service.create(request.scope(), request.projectId(), request.name(),
                request.description(), request.type(), userId,
                userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeProviderResponse.from(provider));
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a draft version")
    public ResponseEntity<KnowledgeProviderVersionResponse> createDraft(
            @PathVariable UUID id,
            @Valid @RequestBody CreateProviderDraftRequest request,
            @CurrentUser UUID userId) {
        var draft = service.createDraft(id, request.connectionConfigId(), request.config(),
                userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeProviderVersionResponse.from(draft));
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Publish the current draft")
    public ResponseEntity<KnowledgeProviderVersionResponse> publish(
            @PathVariable UUID id, @CurrentUser UUID userId) {
        var published = service.publishDraft(id, userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.ok(KnowledgeProviderVersionResponse.from(published));
    }

    @DeleteMapping("/api/v1/admin/knowledge-providers/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Discard the current draft")
    public ResponseEntity<Void> discardDraft(@PathVariable UUID id, @CurrentUser UUID userId) {
        service.discardDraft(id, userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Disable a knowledge provider")
    public ResponseEntity<KnowledgeProviderResponse> disable(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeProviderResponse.from(
                service.disable(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/reenable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Re-enable a knowledge provider")
    public ResponseEntity<KnowledgeProviderResponse> reenable(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeProviderResponse.from(
                service.reenable(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }
}