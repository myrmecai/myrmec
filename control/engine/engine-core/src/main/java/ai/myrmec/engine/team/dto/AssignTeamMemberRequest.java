package ai.myrmec.engine.team.dto;

import ai.myrmec.engine.user.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request DTO for assigning (or replacing) a user's Team-scoped role.
 *
 * <p>Only {@link UserRole.Role#EDITOR} and {@link UserRole.Role#VIEWER} are valid here;
 * {@code SYSTEM_ADMIN} is system-wide and rejected.
 */
@Data
public class AssignTeamMemberRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "role is required")
    private UserRole.Role role;
}
