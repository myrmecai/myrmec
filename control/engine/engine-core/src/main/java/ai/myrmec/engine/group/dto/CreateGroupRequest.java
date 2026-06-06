package ai.myrmec.engine.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * Request to create a group.
 */
@Data
public class CreateGroupRequest {

    @NotBlank(message = "Group name is required")
    @Size(max = 200, message = "Group name cannot exceed 200 characters")
    private String name;

    @Size(max = 3000, message = "Description cannot exceed 3000 characters")
    private String description;

    /**
     * Reserved for future hierarchy support; v1 is flat and this is ignored
     * unless explicitly enabled.
     */
    private UUID parentGroupId;
}
