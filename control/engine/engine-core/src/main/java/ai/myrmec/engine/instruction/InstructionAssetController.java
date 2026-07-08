// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.instruction.dto.CreateDraftRequest;
import ai.myrmec.engine.instruction.dto.CreateInstructionAssetRequest;
import ai.myrmec.engine.instruction.dto.InstructionAssetResponse;
import ai.myrmec.engine.instruction.dto.InstructionAssetVersionResponse;
import ai.myrmec.engine.instruction.dto.UpdateInstructionAssetRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Instruction Asset Controller — admin endpoints for managing AI instruction assets.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Instruction Assets", description = "AI instructions with categories, source types, and applicability")
public class InstructionAssetController {

    private final InstructionAssetService service;

    @GetMapping("/api/v1/admin/instruction-assets")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List instruction assets (optional project filter)")
    public ResponseEntity<List<InstructionAssetResponse>> list(
            @RequestParam(required = false) UUID projectId) {
        if (projectId != null) {
            return ResponseEntity.ok(service.findAllProjectScoped(projectId).stream()
                    .map(InstructionAssetResponse::from)
                    .toList());
        }
        return ResponseEntity.ok(service.findAllOrgScoped().stream()
                .map(InstructionAssetResponse::from)
                .toList());
    }

    @GetMapping("/api/v1/admin/instruction-assets/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get an instruction asset by id")
    public ResponseEntity<InstructionAssetResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(InstructionAssetResponse.from(service.findById(id)));
    }

    @GetMapping("/api/v1/admin/instruction-assets/{id}/published-version")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get the published version of an instruction asset")
    public ResponseEntity<InstructionAssetVersionResponse> getPublishedVersion(@PathVariable UUID id) {
        return ResponseEntity.ok(InstructionAssetVersionResponse.from(service.getPublishedVersion(id)));
    }

    @GetMapping("/api/v1/admin/instruction-assets/{id}/draft-version")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get the draft version of an instruction asset (if any)")
    public ResponseEntity<InstructionAssetVersionResponse> getDraftVersion(@PathVariable UUID id) {
        var draft = service.getDraftVersion(id);
        if (draft == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(InstructionAssetVersionResponse.from(draft));
    }

    @PostMapping("/api/v1/admin/instruction-assets")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a new instruction asset")
    public ResponseEntity<InstructionAssetResponse> create(
            @Valid @RequestBody CreateInstructionAssetRequest request,
            @CurrentUser UUID userId) {
        var asset = service.create(
                request.scope(),
                request.projectId(),
                request.name(),
                request.description(),
                request.category(),
                userId,
                userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InstructionAssetResponse.from(asset));
    }

    @PatchMapping("/api/v1/admin/instruction-assets/{id}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Update Zone-1 parent-row fields (name, description, category)")
    public ResponseEntity<InstructionAssetResponse> update(
            @PathVariable UUID id,
            @RequestBody UpdateInstructionAssetRequest request,
            @CurrentUser UUID userId) {
        var asset = service.update(
                id,
                request.name(),
                request.description(),
                request.category(),
                userId,
                userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.ok(InstructionAssetResponse.from(asset));
    }

    @PostMapping("/api/v1/admin/instruction-assets/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Create a new draft version for an instruction asset")
    public ResponseEntity<InstructionAssetVersionResponse> createDraft(
            @PathVariable UUID id,
            @Valid @RequestBody CreateDraftRequest request,
            @CurrentUser UUID userId) {
        var draft = service.createDraft(
                id,
                request.sourceType(),
                request.sourceDetails(),
                request.connectionConfigId(),
                request.applicability(),
                request.availability(),
                request.priority(),
                request.activationRules(),
                userId,
                userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InstructionAssetVersionResponse.from(draft));
    }

    @PatchMapping("/api/v1/admin/instruction-assets/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Update the current draft version")
    public ResponseEntity<InstructionAssetVersionResponse> updateDraft(
            @PathVariable UUID id,
            @RequestBody CreateDraftRequest request,
            @CurrentUser UUID userId) {
        var draft = service.updateDraft(
                id,
                request.sourceType(),
                request.sourceDetails(),
                request.connectionConfigId(),
                request.applicability(),
                request.availability(),
                request.priority(),
                request.activationRules(),
                userId);
        return ResponseEntity.ok(InstructionAssetVersionResponse.from(draft));
    }

    @PostMapping("/api/v1/admin/instruction-assets/{id}/publish")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Publish the current draft version")
    public ResponseEntity<InstructionAssetVersionResponse> publish(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        var published = service.publishDraft(id, userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.ok(InstructionAssetVersionResponse.from(published));
    }

    @DeleteMapping("/api/v1/admin/instruction-assets/{id}/drafts")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Discard the current draft version")
    public ResponseEntity<Void> discardDraft(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        service.discardDraft(id, userId, userId != null ? userId.toString() : "SYSTEM");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/admin/instruction-assets/{id}/disable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Disable an instruction asset")
    public ResponseEntity<InstructionAssetResponse> disable(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(InstructionAssetResponse.from(
                service.disable(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }

    @PostMapping("/api/v1/admin/instruction-assets/{id}/reenable")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Re-enable an instruction asset")
    public ResponseEntity<InstructionAssetResponse> reenable(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(InstructionAssetResponse.from(
                service.reenable(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }

    @PostMapping("/api/v1/admin/instruction-assets/{id}/archive")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Archive an instruction asset")
    public ResponseEntity<InstructionAssetResponse> archive(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        return ResponseEntity.ok(InstructionAssetResponse.from(
                service.archive(id, userId, userId != null ? userId.toString() : "SYSTEM")));
    }
}