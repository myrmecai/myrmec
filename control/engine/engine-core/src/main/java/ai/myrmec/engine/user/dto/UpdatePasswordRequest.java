package ai.myrmec.engine.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdatePasswordRequest {

    @NotBlank(message = "New password is required")
    private String newPassword;
}
