// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import ai.myrmec.engine.governance.dto.GovernanceProfileResponse;
import ai.myrmec.engine.governance.dto.ProductFeatureResponse;
import ai.myrmec.engine.user.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * Governance Profile Controller — admin endpoints for viewing governance
 * profiles (compare matrix) and selecting the org-level default.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Governance Profiles", description = "AI governance policy profiles (STRICT, STANDARD, FLEXIBLE)")
public class GovernanceProfileController {

    private final GovernanceProfileService service;

    @GetMapping("/api/v1/admin/governance-profiles")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List all governance profiles with feature groups for the compare matrix")
    public ResponseEntity<List<GovernanceProfileResponse>> list() {
        return ResponseEntity.ok(service.findAllWithGroups());
    }

    @GetMapping("/api/v1/admin/governance-profiles/{code}")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get a governance profile by code with feature groups")
    public ResponseEntity<GovernanceProfileResponse> getByCode(@PathVariable String code) {
        return ResponseEntity.ok(service.findByCodeWithGroups(code));
    }

    @GetMapping("/api/v1/admin/governance-profiles/current")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "Get the current default governance profile")
    public ResponseEntity<GovernanceProfileResponse> getCurrent() {
        return ResponseEntity.ok(service.getCurrentDefault());
    }

    @PostMapping("/api/v1/admin/governance-profiles/{code}/set-default")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Set the org-level default governance profile")
    public ResponseEntity<Void> setDefault(
            @PathVariable String code,
            @AuthenticationPrincipal UserPrincipal principal) {
        service.setDefaultProfile(code, principal != null ? principal.getUserId() : null);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/v1/admin/product-features")
    @PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('ORG_ADMIN')")
    @Operation(summary = "List all product features with their valid values")
    public ResponseEntity<List<ProductFeatureResponse>> getProductFeatures() {
        return ResponseEntity.ok(
                Arrays.stream(ProductFeature.values())
                        .map(ProductFeatureResponse::from)
                        .toList());
    }
}