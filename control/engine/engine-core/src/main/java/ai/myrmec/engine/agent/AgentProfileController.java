// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.agent.dto.AgentProfileResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Public (authenticated, non-admin) read surface for agent profiles —
 * the roster authoring and run-view surfaces need to resolve
 * profileCode -> id and label step nodes.
 *
 * <p>Mirrors {@code GET /api/v1/models} (ModelController): the path
 * lives below {@code /api/v1/admin/**}, so the SecurityConfig blanket
 * admin rule does not apply and any authenticated principal passes.
 * The projection is {@link AgentProfileResponse#fromPublic} — identity
 * and published-version pointers only; the behaviour contract (system
 * prompt, tool codes) and draft state stay admin-only.</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Agent Profiles", description = "Read-only agent profile roster")
public class AgentProfileController {

    private final AgentProfileService profileService;
    private final AgentProfileVersionService versionService;

    @GetMapping("/api/v1/agent-profiles")
    @Operation(summary = "List agent profiles (authenticated, public projection)")
    public ResponseEntity<List<AgentProfileResponse>> listProfiles(
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly) {
        List<AgentProfile> profiles = activeOnly
                ? profileService.getActiveProfiles()
                : profileService.getAllProfiles();
        return ResponseEntity.ok(profiles.stream()
                .map(p -> AgentProfileResponse.fromPublic(
                        p,
                        versionService.findPublishedWithTools(p.getId()).orElse(null)))
                .collect(Collectors.toList()));
    }
}