package ai.myrmec.engine.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateUserRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Name is required")
    @Size(min = 1, max = 100, message = "Name must be between 1 and 100 characters")
    private String name;

    @NotBlank(message = "Provider code is required")
    @Size(max = 50, message = "Provider code must be at most 50 characters")
    private String providerCode;

    /**
     * Password is required for LOCAL auth provider.
     */
    private String password;
}
