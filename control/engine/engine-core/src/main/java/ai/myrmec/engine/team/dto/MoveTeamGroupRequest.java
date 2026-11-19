package ai.myrmec.engine.team.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request to move a Team to a different group.
 */
@Data
public class MoveTeamGroupRequest {

    @NotNull(message = "Target groupId is required")
    private UUID groupId;
}
