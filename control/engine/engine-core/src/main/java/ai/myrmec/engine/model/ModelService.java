package ai.myrmec.engine.model;

import ai.myrmec.engine._system.common.DomainConstants;
import ai.myrmec.engine._system.common.DomainConstants.TestStatus;
import ai.myrmec.engine.spi.crypto.EncryptionService;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.model.dto.CreateModelRequest;
import ai.myrmec.engine.model.dto.TestModelResponse;
import ai.myrmec.engine.model.dto.UpdateModelRequest;
import ai.myrmec.engine.secret.SecretPayload;
import ai.myrmec.engine.secret.SecretResolverService;
import ai.myrmec.engine.secret.CredentialType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Service for managing AI model configurations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int CODE_MAX_LENGTH = 50;
    private static final int NAME_MAX_LENGTH = 200;
    private static final int MODEL_ID_MAX_LENGTH = 100;
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private final ModelRepository modelRepository;
    private final ModelProviderConfigRepository providerRepository;
    private final EncryptionService encryptionService;
    private final SecretResolverService secretResolverService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * Find all models with provider info.
     */
    @Transactional(readOnly = true)
    public List<Model> findAll() {
        return modelRepository.findAllWithProvider();
    }

    /**
     * Find all active models (for non-admin users).
     */
    @Transactional(readOnly = true)
    public List<Model> findAllActive() {
        return modelRepository.findAllActive();
    }

    /**
     * Find model by code with provider info.
     */
    @Transactional(readOnly = true)
    public Model findByCode(String code) {
        return modelRepository.findByCodeWithProvider(code)
                .orElseThrow(() -> ResourceNotFoundException.of("Model", "code", code));
    }

    /**
     * Create a new model.
     */
    @Transactional
    public Model create(CreateModelRequest request) {
        validateCode(request.getCode());
        validateName(request.getName());
        validateModelId(request.getModelId());

        if (modelRepository.existsByCode(request.getCode())) {
            throw new BadRequestException("Model with code '" + request.getCode() + "' already exists");
        }

        // Validate provider exists
        ModelProviderConfig providerConfig = providerRepository.findById(request.getProvider())
                .orElseThrow(() -> new BadRequestException("Provider '" + request.getProvider() + "' not found"));

        // Validate deployment type requirements
        validateDeploymentRequirements(request, providerConfig);

        Model model = new Model();
        model.setCode(request.getCode());
        model.setName(request.getName().trim());
        model.setProvider(request.getProvider());
        model.setModelId(request.getModelId().trim());
        
        // Use provider default base URL if not specified
        if (request.getApiEndpoint() != null && !request.getApiEndpoint().isBlank()) {
            model.setApiEndpoint(request.getApiEndpoint().trim());
        } else if (providerConfig.getBaseUrl() != null) {
            model.setApiEndpoint(providerConfig.getBaseUrl());
        }
        
        model.setSupportsVision(request.getSupportsVision() != null && request.getSupportsVision());
        model.setInfraConfig(request.getInfraConfig());
        model.setDefaultParams(request.getDefaultParams());
        model.setInputPrice(request.getInputPrice());
        model.setOutputPrice(request.getOutputPrice());
        model.setCurrency(request.getCurrency() != null ? request.getCurrency() : "USD");
        model.setStatus(ModelStatus.ACTIVE);
        model.setHealthStatus(HealthStatus.UNKNOWN);

        model = modelRepository.save(model);
        log.info("Created model: {} (provider: {})",
                request.getCode(), request.getProvider());
        return model;
    }

    /**
     * Update an existing model.
     * Note: Code, provider, and modelId cannot be changed.
     */
    @Transactional
    public Model update(String code, UpdateModelRequest request) {
        Model model = findByCode(code);

        if (request.getName() != null) {
            validateName(request.getName());
            model.setName(request.getName().trim());
        }

        if (request.getApiEndpoint() != null) {
            model.setApiEndpoint(request.getApiEndpoint().trim().isEmpty() ? null : request.getApiEndpoint().trim());
        }

        if (request.getSupportsVision() != null) {
            model.setSupportsVision(request.getSupportsVision());
        }

        if (request.getInfraConfig() != null) {
            model.setInfraConfig(request.getInfraConfig());
        }

        if (request.getDefaultParams() != null) {
            model.setDefaultParams(request.getDefaultParams());
        }

        if (request.getStatus() != null) {
            model.setStatus(request.getStatus());
        }

        if (request.getInputPrice() != null) model.setInputPrice(request.getInputPrice());
        if (request.getOutputPrice() != null) model.setOutputPrice(request.getOutputPrice());
        if (request.getCurrency() != null) model.setCurrency(request.getCurrency());

        model = modelRepository.save(model);
        log.info("Updated model: {}", code);
        return model;
    }

    /**
     * Delete a model by code.
     */
    @Transactional
    public void delete(String code) {
        Model model = findByCode(code);
        // TODO: Check if model is in use by workflows before deletion
        modelRepository.delete(model);
        log.info("Deleted model: {}", code);
    }

    /**
     * Test model connection.
     */
    @Transactional
    public TestModelResponse testConnection(String code) {
        Model model = findByCode(code);
        ModelProviderConfig providerConfig = providerRepository.findById(model.getProvider()).orElse(null);

        long startTime = System.currentTimeMillis();
        String status;
        String message;

        try {
            String endpoint = getTestEndpoint(model, providerConfig);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(TEST_TIMEOUT)
                    .GET();

            // Resolve credential from the provider's linked ConnectionConfig
            if (providerConfig != null && providerConfig.getConnectionConfigId() != null) {
                String apiKey = getApiKey(code);
                if (apiKey != null) {
                    String authHeader = "Authorization";
                    String authPrefix = "Bearer ";
                    requestBuilder.header(authHeader, authPrefix + apiKey);
                }
            }

            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                status = TestStatus.SUCCESS;
                message = "Connection successful";
            } else {
                status = TestStatus.FAILED;
                message = "HTTP " + response.statusCode() + ": " + response.body();
            }
        } catch (Exception e) {
            status = TestStatus.FAILED;
            message = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("Connection test failed for model {}: {}", code, message);
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        Instant testedAt = Instant.now();

        // Update model with test results
        model.setLastTestedAt(testedAt);
        model.setLastTestStatus(status);
        modelRepository.save(model);

        return TestModelResponse.builder()
                .status(status)
                .latencyMs(latencyMs)
                .message(message)
                .testedAt(testedAt)
                .build();
    }

    /**
     * Update health status for an on-premise model.
     */
    @Transactional
    public void updateHealthStatus(String code, HealthStatus healthStatus) {
        Model model = findByCode(code);
        model.setHealthStatus(healthStatus);
        model.setLastHealthCheck(Instant.now());
        modelRepository.save(model);
    }

    /**
     * Get the decrypted API key for a model, resolving the credential
     * from the provider's linked {@code ConnectionConfig} → secrets vault.
     * Returns {@code null} when the provider has no linked config.
     */
    public String getApiKey(String code) {
        Model model = findByCode(code);
        ModelProviderConfig provider = providerRepository.findById(model.getProvider()).orElse(null);
        if (provider == null || provider.getConnectionConfigId() == null) {
            return null;
        }
        SecretPayload payload = secretResolverService.resolveOneOf(
                provider.getConnectionConfigId(), null,
                CredentialType.BEARER_TOKEN, CredentialType.API_KEY,
                CredentialType.SECRET_KEY);
        return switch (payload) {
            case SecretPayload.BearerToken bt -> bt.token();
            case SecretPayload.ApiKey ak -> ak.key();
            case SecretPayload.SecretKey sk -> sk.secret();
            default -> null;
        };
    }

    private String getTestEndpoint(Model model, ModelProviderConfig providerConfig) {
        String baseEndpoint = model.getApiEndpoint();
        
        // Use provider default if no endpoint specified
        if (baseEndpoint == null && providerConfig != null) {
            baseEndpoint = providerConfig.getBaseUrl();
        }
        
        if (baseEndpoint == null) {
            throw new BadRequestException("API endpoint is required for provider: " + model.getProvider());
        }

        // Check for health endpoint in infra config first
        Map<String, Object> infraConfig = model.getInfraConfig();
        if (infraConfig != null && infraConfig.containsKey("healthEndpoint")) {
            String healthEndpoint = (String) infraConfig.get("healthEndpoint");
            return baseEndpoint.replaceAll("/+$", "") + healthEndpoint;
        }
        
        // Fallback to /v1/models for OpenAI-compatible APIs
        return baseEndpoint.replaceAll("/+$", "") + "/models";
    }

    private void validateCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BadRequestException("Model code is required");
        }
        if (code.length() > CODE_MAX_LENGTH) {
            throw new BadRequestException("Model code cannot exceed " + CODE_MAX_LENGTH + " characters");
        }
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BadRequestException("Model code can only contain letters, numbers, hyphens, and underscores");
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Model name is required");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new BadRequestException("Model name cannot exceed " + NAME_MAX_LENGTH + " characters");
        }
    }

    private void validateModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new BadRequestException("Model ID is required");
        }
        if (modelId.length() > MODEL_ID_MAX_LENGTH) {
            throw new BadRequestException("Model ID cannot exceed " + MODEL_ID_MAX_LENGTH + " characters");
        }
    }

    private void validateDeploymentRequirements(CreateModelRequest request, ModelProviderConfig providerConfig) {
        if (providerConfig.getDeploymentType() == DeploymentType.ON_PREMISE) {
            // On-premise models require endpoint (unless provider has default)
            if ((request.getApiEndpoint() == null || request.getApiEndpoint().isBlank()) 
                    && providerConfig.getBaseUrl() == null) {
                throw new BadRequestException("API endpoint is required for on-premise models");
            }
        } else {
            // Some providers like Azure require endpoint
            if (providerConfig.getBaseUrl() == null 
                    && (request.getApiEndpoint() == null || request.getApiEndpoint().isBlank())) {
                throw new BadRequestException("API endpoint is required for " + providerConfig.getName());
            }
        }
    }
}
