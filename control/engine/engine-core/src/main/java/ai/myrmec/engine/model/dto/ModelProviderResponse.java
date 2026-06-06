package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.DeploymentType;
import ai.myrmec.engine.model.ModelProviderConfig;
import ai.myrmec.engine.model.ModelStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Response DTO for model provider data.
 */
@Data
@Builder
public class ModelProviderResponse {

    private String code;
    private String name;
    private String baseUrl;
    private DeploymentType deploymentType;
    private boolean requiresAuth;
    private String authHeader;
    private String authPrefix;
    private String healthEndpoint;
    private String modelsEndpoint;
    private String docsUrl;
    private String description;
    private boolean isSystem;
    private ModelStatus status;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Convert entity to response DTO.
     */
    public static ModelProviderResponse from(ModelProviderConfig provider) {
        return ModelProviderResponse.builder()
                .code(provider.getCode())
                .name(provider.getName())
                .baseUrl(provider.getBaseUrl())
                .deploymentType(provider.getDeploymentType())
                .requiresAuth(provider.isRequiresAuth())
                .authHeader(provider.getAuthHeader())
                .authPrefix(provider.getAuthPrefix())
                .healthEndpoint(provider.getHealthEndpoint())
                .modelsEndpoint(provider.getModelsEndpoint())
                .docsUrl(provider.getDocsUrl())
                .description(provider.getDescription())
                .isSystem(provider.isSystem())
                .status(provider.getStatus())
                .createdAt(provider.getCreatedAt())
                .updatedAt(provider.getUpdatedAt())
                .build();
    }

    /**
     * Convert entity to response DTO without internal details.
     * Used for public endpoints.
     */
    public static ModelProviderResponse fromPublic(ModelProviderConfig provider) {
        return ModelProviderResponse.builder()
                .code(provider.getCode())
                .name(provider.getName())
                .baseUrl(provider.getBaseUrl())
                .deploymentType(provider.getDeploymentType())
                .requiresAuth(provider.isRequiresAuth())
                .docsUrl(provider.getDocsUrl())
                .description(provider.getDescription())
                .status(provider.getStatus())
                .build();
    }
}
