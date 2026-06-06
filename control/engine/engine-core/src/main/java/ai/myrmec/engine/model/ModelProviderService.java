package ai.myrmec.engine.model;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine.audit.AuditLogService;
import ai.myrmec.engine.model.dto.CreateModelProviderRequest;
import ai.myrmec.engine.model.dto.UpdateModelProviderRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final AuditLogService auditLogService;

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
        if (req.getAuthHeader() != null && !req.getAuthHeader().isBlank()) {
            p.setAuthHeader(req.getAuthHeader());
        }
        if (req.getAuthPrefix() != null) {
            p.setAuthPrefix(req.getAuthPrefix());
        }
        p.setHealthEndpoint(req.getHealthEndpoint());
        p.setModelsEndpoint(req.getModelsEndpoint());
        p.setDocsUrl(req.getDocsUrl());
        p.setDescription(req.getDescription());
        // Admin-created providers are never "system"; only Liquibase
        // seed rows carry that flag.
        p.setSystem(false);
        p.setStatus(ModelStatus.ACTIVE);
        ModelProviderConfig saved = providerRepository.save(p);
        audit("MODEL_PROVIDER_CREATED", saved);
        return saved;
    }

    @Transactional
    public ModelProviderConfig update(String code, UpdateModelProviderRequest req) {
        ModelProviderConfig p = findByCode(code);
        if (req.getName() != null) p.setName(req.getName());
        if (req.getBaseUrl() != null) p.setBaseUrl(req.getBaseUrl());
        if (req.getDeploymentType() != null) p.setDeploymentType(req.getDeploymentType());
        if (req.getRequiresAuth() != null) p.setRequiresAuth(req.getRequiresAuth());
        if (req.getAuthHeader() != null) p.setAuthHeader(req.getAuthHeader());
        if (req.getAuthPrefix() != null) p.setAuthPrefix(req.getAuthPrefix());
        if (req.getHealthEndpoint() != null) p.setHealthEndpoint(req.getHealthEndpoint());
        if (req.getModelsEndpoint() != null) p.setModelsEndpoint(req.getModelsEndpoint());
        if (req.getDocsUrl() != null) p.setDocsUrl(req.getDocsUrl());
        if (req.getDescription() != null) p.setDescription(req.getDescription());
        if (req.getStatus() != null) p.setStatus(req.getStatus());
        ModelProviderConfig saved = providerRepository.save(p);
        audit("MODEL_PROVIDER_UPDATED", saved);
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
        audit("MODEL_PROVIDER_DELETED", p);
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
            auditLogService.record(AuditLogService.AuditEvent.builder()
                    .action(action)
                    .resourceType("ModelProvider")
                    .payload(payload)
                    .build());
        } catch (Exception ex) {
            log.warn("Audit of {} failed (continuing): {}", action, ex.getMessage());
        }
    }
}
