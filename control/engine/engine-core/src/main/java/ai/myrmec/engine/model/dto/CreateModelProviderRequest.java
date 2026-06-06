package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.DeploymentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;

/**
 * Phase 10 #70 &mdash; request body for {@code POST /api/v1/admin/providers}.
 *
 * <p>{@code code} is the persistent identifier (e.g. {@code openai},
 * {@code my-internal-llm}) and is immutable post-create. Restricted to
 * lowercase + dash + underscore + digit so it sits comfortably inside
 * URL paths and config keys.</p>
 */
@Value
@Builder
public class CreateModelProviderRequest {

    @NotBlank
    @Size(max = 50)
    @Pattern(regexp = "^[a-z0-9_-]+$", message = "lowercase + digits + - + _ only")
    String code;

    @NotBlank
    @Size(max = 100)
    String name;

    @Size(max = 500)
    String baseUrl;

    @NotNull
    DeploymentType deploymentType;

    boolean requiresAuth;

    @Size(max = 100)
    String authHeader;

    @Size(max = 50)
    String authPrefix;

    @Size(max = 200)
    String healthEndpoint;

    @Size(max = 200)
    String modelsEndpoint;

    @Size(max = 500)
    String docsUrl;

    String description;
}
