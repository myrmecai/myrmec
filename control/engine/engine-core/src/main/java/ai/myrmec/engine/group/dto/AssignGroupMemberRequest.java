package ai.myrmec.engine.group.dto;

import ai.myrmec.engine.user.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request to assign a group-scoped role to a user.
 */
@Data
public class AssignGroupMemberRequest {

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotNull(message = "role is required")
    private UserRole.Role role;
}
