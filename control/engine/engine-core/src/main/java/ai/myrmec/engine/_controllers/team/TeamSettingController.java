// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._controllers.team;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.team.TeamSetting;
import ai.myrmec.engine.team.TeamSettingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Team Settings", description = "Per-Team key-value settings")
public class TeamSettingController {

    private final TeamSettingService service;

    @GetMapping("/api/v1/teams/{teamId}/settings")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN') or hasRole('EDITOR') or hasRole('VIEWER')")
    @Operation(summary = "List all settings for a Team")
    public ResponseEntity<List<TeamSetting>> list(@PathVariable UUID teamId) {
        return ResponseEntity.ok(service.findAll(teamId));
    }

    @GetMapping("/api/v1/teams/{teamId}/settings/{key}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN') or hasRole('EDITOR') or hasRole('VIEWER')")
    @Operation(summary = "Get a specific setting")
    public ResponseEntity<TeamSetting> get(@PathVariable UUID teamId, @PathVariable String key) {
        return ResponseEntity.ok(service.find(teamId, key));
    }

    @PutMapping("/api/v1/teams/{teamId}/settings/{key}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN') or hasRole('EDITOR')")
    @Operation(summary = "Update a setting value")
    public ResponseEntity<TeamSetting> update(
            @PathVariable UUID teamId,
            @PathVariable String key,
            @RequestBody Map<String, String> body,
            @CurrentUser UUID userId) {
        String value = body.get("value");
        return ResponseEntity.ok(service.update(teamId, key, value, userId));
    }
}