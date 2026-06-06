package ai.myrmec.engine.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateUserRequest {

    @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
    private String name;

    @Size(max = 50, message = "Provider code must be at most 50 characters")
    private String providerCode;

    private Boolean isActive;
}
