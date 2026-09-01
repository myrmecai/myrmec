package ai.myrmec.engine.model;

import ai.myrmec.engine._system.common.DomainConstants;
import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.DomainConstants.TestStatus;
import ai.myrmec.engine._system.common.DomainConstants.ActorType;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.connection.ConnectionConfigService;
import ai.myrmec.engine.connection.dto.TestConnectionResponse;
import ai.myrmec.engine.model.dto.CreateModelProviderRequest;
import ai.myrmec.engine.model.dto.TestModelResponse;
import ai.myrmec.engine.model.dto.UpdateModelProviderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 10 #70 &mdash; admin CRUD for {@link ModelProviderConfig}.
 *
 * <p>Lives separate from {@code ModelService} so the provider
 * surface can evolve without bloating the model service. System
 * providers (seeded by Liquibase) cannot be deleted; they can only
 * be set to {@code INACTIVE} via {@code update()}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelProviderService {

    private final ModelProviderConfigRepository providerRepository;
    private final ModelRepository modelRepository;
    private final AuditEventService auditEventService;
    private final ConnectionConfigService connectionConfigService;

    @Transactional(readOnly = true)
    public List<ModelProviderConfig> findAll() {
        return providerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public ModelProviderConfig findByCode(String code) {
        return providerRepository.findById(code)
                .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + code));
    }

    @Transactional
    public ModelProviderConfig create(CreateModelProviderRequest req) {
        if (providerRepository.existsById(req.getCode())) {
            throw new IllegalArgumentException("Provider already exists: " + req.getCode());
        }
        ModelProviderConfig p = new ModelProviderConfig();
        p.setCode(req.getCode());
        p.setName(req.getName());
        p.setBaseUrl(req.getBaseUrl());
        p.setDeploymentType(req.getDeploymentType());
        p.setRequiresAuth(req.isRequiresAuth());
        p.setDocsUrl(req.getDocsUrl());
        p.setDescription(req.getDescription());
        p.setConnectionConfigId(req.getConnectionConfigId());
        // Admin-created providers are never "system"; only Liquibase
        // seed rows carry that flag.
        p.setSystem(false);
        p.setStatus(ModelStatus.ACTIVE);
        ModelProviderConfig saved = providerRepository.save(p);
        audit(AuditAction.CREATED, saved);
        return saved;
    }

    @Transactional
    public ModelProviderConfig update(String code, UpdateModelProviderRequest req) {
        ModelProviderConfig p = findByCode(code);
        if (req.getName() != null) p.setName(req.getName());
        if (req.getBaseUrl() != null) p.setBaseUrl(req.getBaseUrl());
        if (req.getDeploymentType() != null) p.setDeploymentType(req.getDeploymentType());
        if (req.getRequiresAuth() != null) p.setRequiresAuth(req.getRequiresAuth());
        if (req.getDocsUrl() != null) p.setDocsUrl(req.getDocsUrl());
        if (req.getDescription() != null) p.setDescription(req.getDescription());
        if (req.getStatus() != null) p.setStatus(req.getStatus());
        if (req.getConnectionConfigId() != null) p.setConnectionConfigId(req.getConnectionConfigId());
        ModelProviderConfig saved = providerRepository.save(p);
        audit(AuditAction.UPDATED, saved);
        return saved;
    }

    @Transactional
    public void delete(String code) {
        ModelProviderConfig p = findByCode(code);
        if (p.isSystem()) {
            throw new BadRequestException(
                    "Cannot delete system provider '" + code
                            + "'; set status=INACTIVE instead.");
        }
        long modelCount = modelRepository.findAll().stream()
                .filter(m -> code.equals(m.getProvider()))
                .count();
        if (modelCount > 0) {
            throw ResourceInUseException.blockedBy("Model", (int) modelCount);
        }
        audit(AuditAction.DELETED, p);
        providerRepository.delete(p);
    }

    private void audit(String action, ModelProviderConfig p) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", p.getCode());
        payload.put("name", p.getName());
        payload.put("deploymentType", p.getDeploymentType().name());
        payload.put("status", p.getStatus().name());
        payload.put("system", p.isSystem());
        try {
            auditEventService.recordEvent(ResourceType.MODEL_PROVIDER, null, action,
                    "ORGANIZATION", null, null, ActorType.SYSTEM,
                    null, null, null, null, payload);
        } catch (Exception ex) {
            log.warn("Audit of {} failed (continuing): {}", action, ex.getMessage());
        }
    }

    /**
     * Test a provider's connection by probing its health endpoint.
     * If the provider has a linked ConnectionConfig, delegate to
     * {@link ConnectionConfigService#testConnection(UUID)} to verify
     * the credential + URL. Otherwise, probe the provider's
     * {@code baseUrl} directly (unauthenticated).
     *
     * @param code the provider code
     * @return test result with status, latency, and message
     */
    @Transactional
    public TestModelResponse testConnection(String code) {
        ModelProviderConfig provider = findByCode(code);

        long startTime = System.currentTimeMillis();
        String status;
        String message;

        if (provider.getConnectionConfigId() != null) {
            try {
                TestConnectionResponse response = connectionConfigService
                        .testConnection(provider.getConnectionConfigId());
                status = response.status();
                message = response.error() != null ? response.error() : "Connection successful";
            } catch (Exception e) {
                status = TestStatus.FAILED;
                message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                log.warn("Provider connection test failed for {}: {}", code, message);
            }
        } else {
            // No linked config — probe the provider's base URL directly
            String healthUrl = provider.getBaseUrl();
            if (healthUrl == null || healthUrl.isBlank()) {
                status = TestStatus.SUCCESS;
                message = "No base URL configured — provider is unauthenticated";
            } else {
                try {
                    java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                            .connectTimeout(java.time.Duration.ofSeconds(10))
                            .build();
                    java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(healthUrl))
                            .timeout(java.time.Duration.ofSeconds(10))
                            .GET()
                            .build();
                    java.net.http.HttpResponse<Void> response = client.send(
                            request, java.net.http.HttpResponse.BodyHandlers.discarding());
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        status = TestStatus.SUCCESS;
                        message = "Health endpoint reachable";
                    } else {
                        status = TestStatus.FAILED;
                        message = "HTTP " + response.statusCode();
                    }
                } catch (Exception e) {
                    status = TestStatus.FAILED;
                    message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Provider health probe failed for {}: {}", code, message);
                }
            }
        }

        long latencyMs = System.currentTimeMillis() - startTime;
        Instant testedAt = Instant.now();

        // Audit the test event
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("code", provider.getCode());
        auditPayload.put("status", status);
        auditPayload.put("latencyMs", latencyMs);
        try {
            auditEventService.recordEvent(ResourceType.MODEL_PROVIDER, null,
                    AuditAction.TESTED, "ORGANIZATION",
                    null, null, ActorType.SYSTEM,
                    null, null, null, null, auditPayload);
        } catch (Exception ex) {
            log.warn("Audit of MODEL_PROVIDER_TESTED failed: {}", ex.getMessage());
        }

        return TestModelResponse.builder()
                .status(status)
                .latencyMs(latencyMs)
                .message(message)
                .testedAt(testedAt)
                .build();
    }
}
