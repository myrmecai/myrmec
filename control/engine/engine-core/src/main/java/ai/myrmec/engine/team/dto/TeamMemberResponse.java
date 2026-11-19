package ai.myrmec.engine.team.dto;

import ai.myrmec.engine.user.UserRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * A user with an explicit Team-scoped role.
 */
@Data
@Builder
public class TeamMemberResponse {

    private UUID userId;
    private String email;
    private String name;
    private Boolean isActive;
    private UserRole.Role role;
    /** {@code user_roles.id} of the Team-scoped row — used for the DELETE endpoint. */
    private UUID teamRoleId;
    private UUID grantedByUserId;
    private String grantedByEmail;
    private Instant createdAt;
}
