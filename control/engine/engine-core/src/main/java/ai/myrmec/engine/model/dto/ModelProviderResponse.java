package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.DeploymentType;
import ai.myrmec.engine.model.ModelProviderConfig;
import ai.myrmec.engine.model.ModelStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

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
    @JsonProperty("requiresAuth")
    private boolean requiresAuth;
    private String docsUrl;
    private String description;
    @JsonProperty("isSystem")
    private boolean isSystem;
    private ModelStatus status;
    private UUID connectionConfigId;
    private String connectionConfigName;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * Convert entity to response DTO.
     */
    public static ModelProviderResponse from(ModelProviderConfig provider) {
        return from(provider, null);
    }

    /**
     * Convert entity to response DTO with the linked ConnectionConfig name.
     *
     * @param connectionConfigName the name of the linked ConnectionConfig,
     *        resolved via a QueryDSL left join (null when no config is linked).
     */
    public static ModelProviderResponse from(ModelProviderConfig provider, String connectionConfigName) {
        return ModelProviderResponse.builder()
                .code(provider.getCode())
                .name(provider.getName())
                .baseUrl(provider.getBaseUrl())
                .deploymentType(provider.getDeploymentType())
                .requiresAuth(provider.isRequiresAuth())
                .docsUrl(provider.getDocsUrl())
                .description(provider.getDescription())
                .isSystem(provider.isSystem())
                .status(provider.getStatus())
                .connectionConfigId(provider.getConnectionConfigId())
                .connectionConfigName(connectionConfigName)
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
