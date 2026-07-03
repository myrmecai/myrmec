// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import ai.myrmec.engine._system.security.CurrentUser;
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
@Tag(name = "Project Settings", description = "Per-project key-value settings")
public class ProjectSettingController {

    private final ProjectSettingService service;

    @GetMapping("/api/v1/projects/{projectId}/settings")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN') or hasRole('EDITOR') or hasRole('VIEWER')")
    @Operation(summary = "List all settings for a project")
    public ResponseEntity<List<ProjectSetting>> list(@PathVariable UUID projectId) {
        return ResponseEntity.ok(service.findAll(projectId));
    }

    @GetMapping("/api/v1/projects/{projectId}/settings/{key}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN') or hasRole('EDITOR') or hasRole('VIEWER')")
    @Operation(summary = "Get a specific setting")
    public ResponseEntity<ProjectSetting> get(@PathVariable UUID projectId, @PathVariable String key) {
        return ResponseEntity.ok(service.find(projectId, key));
    }

    @PutMapping("/api/v1/projects/{projectId}/settings/{key}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN') or hasRole('EDITOR')")
    @Operation(summary = "Update a setting value")
    public ResponseEntity<ProjectSetting> update(
            @PathVariable UUID projectId,
            @PathVariable String key,
            @RequestBody Map<String, String> body,
            @CurrentUser UUID userId) {
        String value = body.get("value");
        return ResponseEntity.ok(service.update(projectId, key, value, userId));
    }
}