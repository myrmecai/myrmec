package ai.myrmec.engine.project.dto;

import ai.myrmec.engine.user.UserRole;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * A user with a system-wide role that grants implicit access to every project.
 * Listed for transparency on the project Members page (read-only — managed in System &gt; Users).
 */
@Data
@Builder
public class SystemWideUserSummary {

    private UUID userId;
    private String email;
    private String name;
    /** Highest system-wide role the user holds. */
    private UserRole.Role role;
}
