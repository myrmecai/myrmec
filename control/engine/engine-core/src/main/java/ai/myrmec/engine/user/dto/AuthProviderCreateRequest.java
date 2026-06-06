package ai.myrmec.engine.user.dto;

import ai.myrmec.engine.user.AuthenticationProvider;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class AuthProviderCreateRequest {

    @NotBlank(message = "Provider code is required")
    @Size(max = 50, message = "Provider code must be at most 50 characters")
    private String code;

    @NotNull(message = "Provider type is required")
    private AuthenticationProvider.ProviderType providerType;

    @NotBlank(message = "Provider name is required")
    @Size(max = 100, message = "Provider name must be at most 100 characters")
    private String name;

    @NotNull(message = "Enabled flag is required")
    private Boolean isEnabled;

    private Map<String, Object> metadata;
}
