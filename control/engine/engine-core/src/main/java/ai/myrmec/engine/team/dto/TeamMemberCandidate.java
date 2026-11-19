package ai.myrmec.engine.team.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Minimal user info used by the Team Members "add member" picker.
 */
@Data
@Builder
public class TeamMemberCandidate {

    private UUID userId;
    private String email;
    private String name;
}
