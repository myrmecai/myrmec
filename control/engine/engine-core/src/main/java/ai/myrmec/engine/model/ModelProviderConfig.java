package ai.myrmec.engine.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * AI Model Provider configuration entity.
 * Stores known providers with their default endpoints, auth settings, and metadata.
 */
@Entity
@Table(name = "model_providers")
@Getter
@Setter
@NoArgsConstructor
public class ModelProviderConfig {

    /**
     * Unique provider identifier (e.g., openai, anthropic, github_models).
     */
    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    /**
     * Display name for the provider.
     */
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    /**
     * Default base URL for the provider's API.
     * Can be overridden per model. May be null for providers like Azure OpenAI
     * where endpoint varies per deployment.
     */
    @Column(name = "base_url", length = 500)
    private String baseUrl;

    /**
     * Deployment type: CLOUD or ON_PREMISE.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_type", nullable = false, length = 20)
    private DeploymentType deploymentType = DeploymentType.CLOUD;

    /**
     * Whether this provider requires authentication.
     * Most cloud providers require auth; some on-premise don't.
     */
    @Column(name = "requires_auth", nullable = false)
    private boolean requiresAuth = true;

    /**
     * HTTP header name for authentication (e.g., Authorization, x-api-key).
     */
    @Column(name = "auth_header", nullable = false, length = 100)
    private String authHeader = "Authorization";

    /**
     * Prefix for the auth header value (e.g., "Bearer ", "").
     */
    @Column(name = "auth_prefix", nullable = false, length = 50)
    private String authPrefix = "Bearer ";

    /**
     * Default health check endpoint path (e.g., /health, /models).
     */
    @Column(name = "health_endpoint", length = 200)
    private String healthEndpoint;

    /**
     * Endpoint to list available models (e.g., /models, /api/tags).
     */
    @Column(name = "models_endpoint", length = 200)
    private String modelsEndpoint;

    /**
     * URL to provider's API documentation.
     */
    @Column(name = "docs_url", length = 500)
    private String docsUrl;

    /**
     * Description of the provider and its capabilities.
     */
    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * System-provided providers cannot be deleted (only disabled).
     */
    @Column(name = "is_system", nullable = false)
    private boolean isSystem = true;

    /**
     * Provider status: ACTIVE or INACTIVE.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ModelStatus status = ModelStatus.ACTIVE;

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
