// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.common.DomainConstants;
import ai.myrmec.engine._system.common.DomainConstants.ActorType;
import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.knowledge.dto.CreateKnowledgeProviderRequest;
import ai.myrmec.engine.knowledge.dto.CreateProviderDraftRequest;
import ai.myrmec.engine.knowledge.dto.KnowledgeProviderResponse;
import ai.myrmec.engine.knowledge.dto.KnowledgeProviderVersionResponse;
import ai.myrmec.engine.knowledge.dto.KnowledgeSourceResponse;
import ai.myrmec.engine.knowledge.dto.UpdateKnowledgeProviderRequest;
import ai.myrmec.engine.knowledge.dto.UpdateProviderDraftRequest;
import ai.myrmec.engine.knowledge.dto.CreateKnowledgeSourceRequest;
import ai.myrmec.engine.knowledge.dto.UpdateKnowledgeSourceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Knowledge Providers", description = "RAG providers (MANAGED/EXTERNAL) with versioned configs")
public class KnowledgeProviderController {

    private final KnowledgeProviderService service;

    @GetMapping("/api/v1/admin/knowledge-providers")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List knowledge providers (optional project filter)")
    public ResponseEntity<List<KnowledgeProviderResponse>> list(
            @RequestParam(required = false) UUID projectId) {
        if (projectId != null) {
            return ResponseEntity.ok(service.findAllProjectScoped(projectId).stream()
                    .map(KnowledgeProviderResponse::from).toList());
        }
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
        var published = service.getPublishedVersionOrNull(id);
        if (published == null) return ResponseEntity.noContent().build();
        return ResponseEntity.ok(KnowledgeProviderVersionResponse.from(published));
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
                userId != null ? userId.toString() : ActorType.SYSTEM);
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
                userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeProviderVersionResponse.from(draft));
    }

    @PatchMapping("/api/v1/admin/knowledge-providers/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Update the current draft (Zone 2 config)")
    public ResponseEntity<KnowledgeProviderVersionResponse> updateDraft(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProviderDraftRequest request,
            @CurrentUser UUID userId) {
        var updated = service.updateDraft(id, request.connectionConfigId(), request.config(),
                userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.ok(KnowledgeProviderVersionResponse.from(updated));
    }

    @PatchMapping("/api/v1/admin/knowledge-providers/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Update Zone-1 parent-row fields (name, description)")
    public ResponseEntity<KnowledgeProviderResponse> updateZone1(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateKnowledgeProviderRequest request,
            @CurrentUser UUID userId) {
        var updated = service.updateZone1(id, request.name(), request.description(),
                userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.ok(KnowledgeProviderResponse.from(updated));
    }

    @GetMapping("/api/v1/admin/knowledge-providers/{id}/versions")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get version history")
    public ResponseEntity<List<KnowledgeProviderVersionResponse>> getVersionHistory(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getVersionHistory(id).stream()
                .map(KnowledgeProviderVersionResponse::from).toList());
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/versions/{versionId}/clone")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Clone an archived version as new Draft")
    public ResponseEntity<KnowledgeProviderVersionResponse> cloneVersion(
            @PathVariable UUID id, @PathVariable UUID versionId,
            @CurrentUser UUID userId) {
        var draft = service.cloneVersion(id, versionId,
                userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeProviderVersionResponse.from(draft));
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/archive")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Archive a disabled provider")
    public ResponseEntity<KnowledgeProviderResponse> archive(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeProviderResponse.from(
                service.archive(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM)));
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/unarchive")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Un-archive (rollback to DISABLED)")
    public ResponseEntity<KnowledgeProviderResponse> unarchive(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeProviderResponse.from(
                service.unarchive(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM)));
    }

    @DeleteMapping("/api/v1/admin/knowledge-providers/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Delete a knowledge provider (cleanup/e2e)")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @CurrentUser UUID userId) {
        service.delete(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.noContent().build();
    }

    // ---- Knowledge Sources (nested under provider) ----

    @GetMapping("/api/v1/admin/knowledge-providers/{id}/knowledge-sources")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List knowledge sources linked to this provider")
    public ResponseEntity<List<KnowledgeSourceResponse>> getKnowledgeSources(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getKnowledgeSources(id).stream()
                .map(KnowledgeSourceResponse::from).toList());
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/knowledge-sources")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Add a knowledge source to this provider")
    public ResponseEntity<KnowledgeSourceResponse> addKnowledgeSource(
            @PathVariable UUID id,
            @Valid @RequestBody CreateKnowledgeSourceRequest request,
            @CurrentUser UUID userId) {
        var source = service.addKnowledgeSource(id, request.name(), request.description(),
                request.config(), userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.status(HttpStatus.CREATED).body(KnowledgeSourceResponse.from(source));
    }

    @PatchMapping("/api/v1/admin/knowledge-sources/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Update a knowledge source")
    public ResponseEntity<KnowledgeSourceResponse> updateKnowledgeSource(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateKnowledgeSourceRequest request,
            @CurrentUser UUID userId) {
        var updated = service.updateKnowledgeSource(id, request.name(), request.description(),
                request.config(), userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.ok(KnowledgeSourceResponse.from(updated));
    }

    @DeleteMapping("/api/v1/admin/knowledge-sources/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Delete a knowledge source")
    public ResponseEntity<Void> deleteKnowledgeSource(@PathVariable UUID id, @CurrentUser UUID userId) {
        service.deleteKnowledgeSource(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Publish the current draft")
    public ResponseEntity<KnowledgeProviderVersionResponse> publish(
            @PathVariable UUID id, @CurrentUser UUID userId) {
        var published = service.publishDraft(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.ok(KnowledgeProviderVersionResponse.from(published));
    }

    @DeleteMapping("/api/v1/admin/knowledge-providers/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Discard the current draft")
    public ResponseEntity<Void> discardDraft(@PathVariable UUID id, @CurrentUser UUID userId) {
        service.discardDraft(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Disable a knowledge provider")
    public ResponseEntity<KnowledgeProviderResponse> disable(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeProviderResponse.from(
                service.disable(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM)));
    }

    @PostMapping("/api/v1/admin/knowledge-providers/{id}/reenable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Re-enable a knowledge provider")
    public ResponseEntity<KnowledgeProviderResponse> reenable(@PathVariable UUID id, @CurrentUser UUID userId) {
        return ResponseEntity.ok(KnowledgeProviderResponse.from(
                service.reenable(id, userId, userId != null ? userId.toString() : ActorType.SYSTEM)));
    }
}