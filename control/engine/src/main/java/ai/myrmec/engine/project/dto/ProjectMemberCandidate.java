package ai.myrmec.engine.project.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Minimal user info used by the project Members "add member" picker.
 */
@Data
@Builder
public class ProjectMemberCandidate {

    private UUID userId;
    private String email;
    private String name;
}
