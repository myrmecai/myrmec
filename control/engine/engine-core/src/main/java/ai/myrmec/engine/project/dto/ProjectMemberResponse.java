package ai.myrmec.engine.project.dto;

import ai.myrmec.engine.user.UserRole;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * A user with an explicit project-scoped role.
 */
@Data
@Builder
public class ProjectMemberResponse {

    private UUID userId;
    private String email;
    private String name;
    private Boolean isActive;
    private UserRole.Role role;
    /** {@code user_roles.id} of the project-scoped row — used for the DELETE endpoint. */
    private UUID projectRoleId;
    private UUID grantedByUserId;
    private String grantedByEmail;
    private Instant createdAt;
}
