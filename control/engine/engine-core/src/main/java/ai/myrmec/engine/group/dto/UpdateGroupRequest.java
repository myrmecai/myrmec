package ai.myrmec.engine.group.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request to update group metadata. Only fields provided are applied.
 */
@Data
public class UpdateGroupRequest {

    @Size(max = 200, message = "Group name cannot exceed 200 characters")
    private String name;

    @Size(max = 3000, message = "Description cannot exceed 3000 characters")
    private String description;
}
