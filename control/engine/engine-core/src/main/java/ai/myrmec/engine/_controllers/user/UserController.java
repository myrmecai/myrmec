// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine._controllers.user;

import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.dto.CurrentUserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User-facing endpoints for the authenticated principal.
 *
 * <p>This controller mirrors the mock's {@code /api/v1/users/current} path so
 * the VS Code plugin can validate its session against the same URL.
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "Current User", description = "Authenticated principal endpoints")
public class UserController {

    @GetMapping("/current")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the currently authenticated user")
    public ResponseEntity<CurrentUserResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        CurrentUserResponse response = CurrentUserResponse.builder()
                .userId(principal.getUserId())
                .email(principal.getEmail())
                .name(principal.getName())
                .roles(principal.getRoles())
                .build();
        return ResponseEntity.ok(response);
    }
}
