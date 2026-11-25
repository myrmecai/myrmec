// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine._controllers.team;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import ai.myrmec.engine.team.TeamInstructionBinding;
import ai.myrmec.engine.team.TeamInstructionBindingService;

import java.util.List;
import java.util.UUID;

/**
 * REST controller for Team instruction bindings — enables/disables
 * org OPTIONAL instruction assets per Team.
 */
@RestController
@RequestMapping("/api/v1/admin/teams/{teamId}/instruction-bindings")
@RequiredArgsConstructor
@Tag(name = "Team Instruction Bindings", description = "Enable/disable org OPTIONAL instruction assets per Team")
public class TeamInstructionBindingController {

    private final TeamInstructionBindingService service;

    @GetMapping
    @PreAuthorize("@teamAccess.canView(#teamId, authentication)")
    @Operation(summary = "List instruction bindings for a Team")
    public ResponseEntity<List<TeamInstructionBindingResponse>> list(
            @PathVariable("teamId") UUID teamId) {
        return ResponseEntity.ok(service.findAllByTeamId(teamId).stream()
                .map(TeamInstructionBindingResponse::from)
                .toList());
    }

    @PutMapping("/{assetId}")
    @PreAuthorize("@teamAccess.canEdit(#teamId, authentication) or hasRole('ORG_ADMIN')")
    @Operation(summary = "Upsert an instruction binding (enable/disable an org OPTIONAL asset for this Team)")
    public ResponseEntity<TeamInstructionBindingResponse> upsert(
            @PathVariable("teamId") UUID teamId,
            @PathVariable("assetId") UUID assetId,
            @Valid @RequestBody UpsertBindingRequest request) {
        return ResponseEntity.ok(
                TeamInstructionBindingResponse.from(
                        service.upsert(teamId, assetId, request.enabled())));
    }

    public record UpsertBindingRequest(@NotNull Boolean enabled) {}

    public record TeamInstructionBindingResponse(
            UUID id,
            UUID teamId,
            UUID instructionAssetId,
            Boolean enabled) {

        public static TeamInstructionBindingResponse from(TeamInstructionBinding b) {
            return new TeamInstructionBindingResponse(
                    b.getId(),
                    b.getTeamId(),
                    b.getInstructionAssetId(),
                    b.getEnabled());
        }
    }
}