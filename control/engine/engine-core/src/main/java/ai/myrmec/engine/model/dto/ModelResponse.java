package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * Response DTO for model data.
 * Note: apiKey is never included in responses.
 */
@Data
@Builder
public class ModelResponse {

    private String code;
    private String name;
    private String provider;
    private String providerName;
    private String modelId;
    private String apiEndpoint;
    @JsonProperty("supportsVision")
    private boolean supportsVision;
    private Map<String, Object> infraConfig;
    private Map<String, Object> defaultParams;
    private ModelStatus status;
    private HealthStatus healthStatus;
    private Instant lastHealthCheck;
    private Instant lastTestedAt;
    private String lastTestStatus;
    private Instant createdAt;
    private Instant updatedAt;

    // Admin-only pricing fields (not included in fromPublic())
    private java.math.BigDecimal inputPrice;
    private java.math.BigDecimal outputPrice;
    private String currency;

    /**
     * Convert entity to response DTO.
     * API key is never exposed.
     */
    public static ModelResponse from(Model model) {
        var providerConfig = model.getProviderConfig();
        return ModelResponse.builder()
                .code(model.getCode())
                .name(model.getName())
                .provider(model.getProvider())
                .providerName(providerConfig != null ? providerConfig.getName() : model.getProvider())
                .modelId(model.getModelId())
                .apiEndpoint(model.getApiEndpoint())
                .supportsVision(model.isSupportsVision())
                .infraConfig(model.getInfraConfig())
                .defaultParams(model.getDefaultParams())
                .status(model.getStatus())
                .healthStatus(model.getHealthStatus())
                .lastHealthCheck(model.getLastHealthCheck())
                .lastTestedAt(model.getLastTestedAt())
                .lastTestStatus(model.getLastTestStatus())
                .createdAt(model.getCreatedAt())
                .updatedAt(model.getUpdatedAt())
                .inputPrice(model.getInputPrice())
                .outputPrice(model.getOutputPrice())
                .currency(model.getCurrency())
                .build();
    }

    /**
     * Convert entity to response DTO without sensitive information.
     * Used for non-admin endpoints.
     */
    public static ModelResponse fromPublic(Model model) {
        var providerConfig = model.getProviderConfig();
        return ModelResponse.builder()
                .code(model.getCode())
                .name(model.getName())
                .provider(model.getProvider())
                .providerName(providerConfig != null ? providerConfig.getName() : model.getProvider())
                .modelId(model.getModelId())
                .status(model.getStatus())
                .healthStatus(model.getHealthStatus())
                .defaultParams(model.getDefaultParams())
                .supportsVision(model.isSupportsVision())
                .build();
    }
}
