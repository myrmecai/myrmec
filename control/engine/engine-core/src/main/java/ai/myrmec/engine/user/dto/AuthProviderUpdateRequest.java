package ai.myrmec.engine.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

@Data
public class AuthProviderUpdateRequest {

    @Size(max = 100, message = "Provider name must be at most 100 characters")
    private String name;

    private Boolean isEnabled;

    private Map<String, Object> metadata;
}
