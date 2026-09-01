// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.project;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for project instruction bindings — enables/disables
 * org OPTIONAL instruction assets per project.
 */
@RestController
@RequestMapping("/api/v1/admin/projects/{projectId}/instruction-bindings")
@RequiredArgsConstructor
@Tag(name = "Project Instruction Bindings", description = "Enable/disable org OPTIONAL instruction assets per project")
public class ProjectInstructionBindingController {

    private final ProjectInstructionBindingService service;

    @GetMapping
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    @Operation(summary = "List instruction bindings for a project")
    public ResponseEntity<List<ProjectInstructionBindingResponse>> list(
            @PathVariable("projectId") UUID projectId) {
        return ResponseEntity.ok(service.findAllByProjectId(projectId).stream()
                .map(ProjectInstructionBindingResponse::from)
                .toList());
    }

    @PutMapping("/{assetId}")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication) or hasRole('ORG_ADMIN')")
    @Operation(summary = "Upsert an instruction binding (enable/disable an org OPTIONAL asset for this project)")
    public ResponseEntity<ProjectInstructionBindingResponse> upsert(
            @PathVariable("projectId") UUID projectId,
            @PathVariable("assetId") UUID assetId,
            @Valid @RequestBody UpsertBindingRequest request) {
        return ResponseEntity.ok(
                ProjectInstructionBindingResponse.from(
                        service.upsert(projectId, assetId, request.enabled())));
    }

    public record UpsertBindingRequest(@NotNull Boolean enabled) {}

    public record ProjectInstructionBindingResponse(
            UUID id,
            UUID projectId,
            UUID instructionAssetId,
            Boolean enabled) {

        public static ProjectInstructionBindingResponse from(ProjectInstructionBinding b) {
            return new ProjectInstructionBindingResponse(
                    b.getId(),
                    b.getProjectId(),
                    b.getInstructionAssetId(),
                    b.getEnabled());
        }
    }
}