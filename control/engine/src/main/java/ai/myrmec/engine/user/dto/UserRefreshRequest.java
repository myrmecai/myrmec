package ai.myrmec.engine.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserRefreshRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
