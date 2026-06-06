package ai.myrmec.engine.project.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request to move a project to a different group.
 */
@Data
public class MoveProjectGroupRequest {

    @NotNull(message = "Target groupId is required")
    private UUID groupId;
}
