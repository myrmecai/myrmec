package ai.myrmec.engine.user.dto;

import ai.myrmec.engine.user.UserRole;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request to assign a role to a user. Supply exactly one of:
 * <ul>
 *   <li>{@code scopeType=SYSTEM} — neither {@code groupId} nor {@code projectId}</li>
 *   <li>{@code scopeType=GROUP}  — {@code groupId} required</li>
 *   <li>{@code scopeType=PROJECT} — {@code projectId} required</li>
 * </ul>
 */
@Data
public class AssignRoleRequest {

    @NotNull(message = "Role is required")
    private UserRole.Role role;

    @NotNull(message = "scopeType is required")
    private UserRole.ScopeType scopeType;

    private UUID groupId;

    private UUID projectId;
}
