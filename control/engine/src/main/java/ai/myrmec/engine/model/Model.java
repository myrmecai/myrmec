package ai.myrmec.engine.model;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;

/**
 * AI Model configuration entity.
 * Stores connection details for LLM providers (cloud and on-premise).
 */
@Entity
@Table(name = "models")
@Getter
@Setter
@NoArgsConstructor
public class Model {

    /**
     * User-defined unique identifier.
     * Pattern: alphanumeric, underscore, and hyphen only.
     */
    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /**
     * Provider code (FK to model_providers.code).
     */
    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    /**
     * Reference to the provider configuration.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider", referencedColumnName = "code", insertable = false, updatable = false)
    private ModelProviderConfig providerConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_type", nullable = false, length = 20)
    private DeploymentType deploymentType = DeploymentType.CLOUD;

    /**
     * Provider-specific model identifier (e.g., gpt-4, llama3:70b)
     */
    @Column(name = "model_id", nullable = false, length = 100)
    private String modelId;

    /**
     * API endpoint URL. Required for ON_PREMISE, optional for CLOUD.
     */
    @Column(name = "api_endpoint", length = 500)
    private String apiEndpoint;

    /**
     * Encrypted API key (AES-256-GCM).
     * Required for CLOUD, optional for ON_PREMISE.
     */
    @Column(name = "api_key_encrypted")
    private byte[] apiKeyEncrypted;

    /**
     * Whether authentication is required.
     */
    @Column(name = "requires_auth", nullable = false)
    private boolean requiresAuth = true;

    /**
     * Infrastructure configuration for on-premise deployments.
     * Contains GPU type, memory, quantization, health endpoint, etc.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "infra_config")
    private Map<String, Object> infraConfig;

    /**
     * Default inference parameters (temperature, maxTokens, etc.)
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "default_params")
    private Map<String, Object> defaultParams;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ModelStatus status = ModelStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 20)
    private HealthStatus healthStatus = HealthStatus.UNKNOWN;

    @Column(name = "last_health_check")
    private Instant lastHealthCheck;

    @Column(name = "last_tested_at")
    private Instant lastTestedAt;

    @Column(name = "last_test_status", length = 20)
    private String lastTestStatus;

    /**
     * Input (prompt) token price per 1,000 tokens, in {@link #currency}.
     * Used for cost computation on task completion. Optional.
     */
    @Column(name = "input_price_per_1k_tokens", precision = 10, scale = 6)
    private BigDecimal inputPricePer1kTokens;

    /**
     * Output (completion) token price per 1,000 tokens, in {@link #currency}.
     */
    @Column(name = "output_price_per_1k_tokens", precision = 10, scale = 6)
    private BigDecimal outputPricePer1kTokens;

    /**
     * ISO 4217 currency code for pricing (e.g. USD).
     */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
