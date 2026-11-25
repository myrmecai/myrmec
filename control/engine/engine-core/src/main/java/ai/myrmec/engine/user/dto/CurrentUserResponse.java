// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Response for the currently authenticated user.
 *
 * <p>Mirrors the mock's {@code GET /api/v1/users/current} contract used by the
 * VS Code plugin to validate its session.
 */
@Data
@Builder
@Schema(name = "CurrentUserResponse", description = "Authenticated principal details")
public class CurrentUserResponse {

    @Schema(description = "User identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID userId;

    @Schema(description = "User email address", example = "dev@example.com")
    private String email;

    @Schema(description = "Display name", example = "Demo User")
    private String name;

    @Schema(description = "Effective JWT role claims (scope-prefixed)")
    private List<String> roles;
}
