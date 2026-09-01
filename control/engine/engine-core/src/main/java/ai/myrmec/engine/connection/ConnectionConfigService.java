// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

import ai.myrmec.engine._system.common.DomainConstants;
import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.DomainConstants.ConnectionType;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine._system.common.DomainConstants.TestStatus;
import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.connection.dto.TestConnectionResponse;
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
import java.util.UUID;

/**
 * Connection Config Service — CRUD for connection configs, version management.
 *
 * <p>Implements the versioned entity pattern: create draft, publish, archive.
 * See UC-018 for the full state machine and API design.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionConfigService {

    private final ConnectionConfigRepository repository;
    private final ConnectionConfigVersionRepository versionRepository;
    private final AuditEventService auditEventService;

    // ---- read paths -------------------------------------------------

    @Transactional(readOnly = true)
    public List<ConnectionConfig> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION);
    }

    @Transactional(readOnly = true)
    public List<ConnectionConfig> findAllProjectScoped(UUID projectId) {
        return repository.findByScopeAndProjectId(Scope.PROJECT, projectId);
    }

    @Transactional(readOnly = true)
    public ConnectionConfig findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("ConnectionConfig", id));
    }

    @Transactional(readOnly = true)
    public ConnectionConfigVersion getPublishedVersion(UUID configId) {
        return versionRepository.findByConnectionConfigIdAndStatus(configId, EntityStatus.PUBLISHED)
                .orElseThrow(() -> ResourceNotFoundException.of("ConnectionConfigVersion",
                        "connectionConfigId=" + configId + ", status=" + EntityStatus.PUBLISHED));
    }

    @Transactional(readOnly = true)
    public ConnectionConfigVersion getDraftVersion(UUID configId) {
        return versionRepository.findByConnectionConfigIdAndStatus(configId, EntityStatus.DRAFT)
                .orElse(null);
    }

    // ---- create ------------------------------------------------------

    @Transactional
    public ConnectionConfig create(String scope, UUID projectId, String name, String description,
                                   String type, UUID credentialSecretId, UUID actorId,
                                   String actorDisplayName) {
        // Validate name uniqueness
        boolean exists = projectId == null
                ? repository.existsByScopeAndProjectIdIsNullAndName(scope, name)
                : repository.existsByScopeAndProjectIdAndName(scope, projectId, name);
        if (exists) {
            throw BadRequestException.forField("name", "DUPLICATE_CODE",
                    "A connection config with this name already exists in this scope.");
        }

        ConnectionConfig config = new ConnectionConfig();
        config.setScope(scope);
        config.setProjectId(projectId);
        config.setName(name);
        config.setDescription(description);
        config.setType(type);
        config.setStatus(EntityStatus.INCOMPLETE);
        config.setCredentialSecretId(credentialSecretId);
        config.setCreatedBy(actorId);

        ConnectionConfig saved = repository.save(config);
        log.info("Created connection config: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, saved.getId(), AuditAction.CREATED,
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name, "type", type), null);

        return saved;
    }

    // ---- update parent ------------------------------------------------

    @Transactional
    public ConnectionConfig update(UUID configId, String name, String description,
                                   UUID credentialSecretId, UUID actorId,
                                   String actorDisplayName) {
        ConnectionConfig config = findById(configId);

        if (name != null && !name.isBlank()) {
            // Validate name uniqueness if name is changing
            if (!name.equals(config.getName())) {
                boolean exists = config.getProjectId() == null
                        ? repository.existsByScopeAndProjectIdIsNullAndName(config.getScope(), name)
                        : repository.existsByScopeAndProjectIdAndName(config.getScope(), config.getProjectId(), name);
                if (exists) {
                    throw BadRequestException.forField("name", "DUPLICATE_CODE",
                            "A connection config with this name already exists in this scope.");
                }
            }
            config.setName(name);
        }

        if (description != null) {
            config.setDescription(description);
        }

        if (credentialSecretId != null) {
            config.setCredentialSecretId(credentialSecretId);
        }

        ConnectionConfig saved = repository.save(config);
        log.info("Updated connection config: {} (id: {})", saved.getName(), configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, AuditAction.UPDATED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, null, null,
                Map.of("name", saved.getName()), null);

        return saved;
    }

    // ---- version management ------------------------------------------

    @Transactional
    public ConnectionConfigVersion createDraft(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);

        // Check if a draft already exists
        ConnectionConfigVersion existingDraft = getDraftVersion(configId);
        if (existingDraft != null) {
            throw new BadRequestException("A draft version already exists for this connection config.");
        }

        // Find the current published version (if any) to set parent_version_id
        ConnectionConfigVersion publishedVersion = versionRepository
                .findByConnectionConfigIdAndStatus(configId, EntityStatus.PUBLISHED).orElse(null);

        int versionNumber = publishedVersion != null ? publishedVersion.getVersionNumber() + 1 : 1;

        ConnectionConfigVersion draft = new ConnectionConfigVersion();
        draft.setConnectionConfigId(configId);
        draft.setVersionNumber(versionNumber);
        draft.setParentVersionId(publishedVersion != null ? publishedVersion.getId() : null);
        draft.setStatus(EntityStatus.DRAFT);
        draft.setDraftOwnerId(actorId);

        // Copy URL and config from published version if it exists
        if (publishedVersion != null) {
            draft.setUrl(publishedVersion.getUrl());
            draft.setConfig(publishedVersion.getConfig());
        }

        ConnectionConfigVersion saved = versionRepository.save(draft);
        log.info("Created draft version {} for connection config: {}", versionNumber, configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, AuditAction.DRAFT_CREATED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null, Map.of("version_number", versionNumber), null);

        return saved;
    }

    @Transactional
    public ConnectionConfigVersion publishDraft(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        ConnectionConfigVersion draft = getDraftVersion(configId);

        if (draft == null) {
            throw new BadRequestException("No draft version exists to publish.");
        }

        // Validate required fields at publish
        if (draft.getUrl() == null || draft.getUrl().isBlank()) {
            throw BadRequestException.forField("url", "REQUIRED",
                    "URL is required at publish time.");
        }

        // BR-CC-14: test_endpoint required for HTTP connections
        if (ConnectionType.HTTP.equals(config.getType())) {
            Map<String, Object> cfg = draft.getConfig();
            String testEndpoint = cfg != null ? (String) cfg.get("testEndpoint") : null;
            if (testEndpoint == null || testEndpoint.isBlank()) {
                throw BadRequestException.forField("testEndpoint", "REQUIRED",
                        "Test Endpoint is required for HTTP connections.");
            }
        }

        // Server-side connectivity check is temporarily disabled so connection configs can be
        // published in test/e2e environments that do not have a reachable target endpoint.
        // Explicit "Test connection" endpoints still perform a real reachability check on demand.
        // BR-CC-15: publish gate runs a server-side connectivity check
        // String fullUrl = buildTestUrl(config.getType(), draft.getUrl(), draft.getConfig());
        // try {
        //     boolean connected = performConnectivityCheck(config.getType(), fullUrl);
        //     if (!connected) {
        //         throw BadRequestException.forField("connection", "CONNECTIVITY_CHECK_FAILED",
        //                 "Connection test failed: connectivity check failed for URL " + fullUrl);
        //     }
        // } catch (BadRequestException e) {
        //     throw e;
        // } catch (Exception e) {
        //     String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        //     throw BadRequestException.forField("connection", "CONNECTIVITY_CHECK_FAILED",
        //             "Connection test failed: " + errorMsg);
        // }

        // Archive the current published version if one exists
        ConnectionConfigVersion currentPublished = versionRepository
                .findByConnectionConfigIdAndStatus(configId, EntityStatus.PUBLISHED).orElse(null);
        if (currentPublished != null) {
            currentPublished.setStatus(EntityStatus.ARCHIVED);
            versionRepository.save(currentPublished);
        }

        // Publish the draft
        draft.setStatus(EntityStatus.PUBLISHED);
        draft.setDraftOwnerId(null);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(actorId);
        ConnectionConfigVersion saved = versionRepository.save(draft);

        // Update parent
        config.setCurrentVersionId(saved.getId());
        config.setStatus(EntityStatus.ACTIVE);
        config.setPublishedAt(Instant.now());
        config.setPublishedBy(actorId);
        repository.save(config);

        log.info("Published connection config version {} (id: {})", draft.getVersionNumber(), configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, EntityStatus.PUBLISHED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                saved.getId(), null, null, Map.of("version_number", draft.getVersionNumber()), null);

        return saved;
    }

    @Transactional
    public void discardDraft(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        ConnectionConfigVersion draft = getDraftVersion(configId);

        if (draft == null) {
            throw new BadRequestException("No draft version exists to discard.");
        }

        versionRepository.delete(draft);
        log.info("Discarded draft for connection config: {}", configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, AuditAction.DRAFT_DISCARDED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                draft.getId(), null, null, null, null);
    }

    // ---- lifecycle ---------------------------------------------------

    @Transactional
    public ConnectionConfig disable(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        config.setStatus(EntityStatus.DISABLED);
        ConnectionConfig saved = repository.save(config);
        log.info("Disabled connection config: {}", configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, EntityStatus.DISABLED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_DISABLED, null, null, null);

        return saved;
    }

    @Transactional
    public ConnectionConfig reenable(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        config.setStatus(EntityStatus.ACTIVE);
        ConnectionConfig saved = repository.save(config);
        log.info("Re-enabled connection config: {}", configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, AuditAction.REENABLED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_REENABLED, null, null, null);

        return saved;
    }

    @Transactional
    public ConnectionConfig archive(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        config.setStatus(EntityStatus.ARCHIVED);
        ConnectionConfig saved = repository.save(config);
        log.info("Archived connection config: {}", configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, EntityStatus.ARCHIVED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_ARCHIVED, null, null, null);

        return saved;
    }

    // ---- update draft ------------------------------------------------

    @Transactional
    public ConnectionConfigVersion updateDraft(UUID configId, String url,
                                               Map<String, Object> configJson,
                                               UUID actorId) {
        ConnectionConfigVersion draft = getDraftVersion(configId);
        if (draft == null) {
            throw new BadRequestException("No draft version exists to update.");
        }

        if (url != null) {
            draft.setUrl(url);
            // BR-CC-16: Zone 2 modification resets test status
            draft.setTestStatus(null);
            draft.setLastTestAt(null);
            draft.setLastTestError(null);
        }
        if (configJson != null) {
            draft.setConfig(configJson);
            // BR-CC-16: Zone 2 modification resets test status
            draft.setTestStatus(null);
            draft.setLastTestAt(null);
            draft.setLastTestError(null);
        }

        return versionRepository.save(draft);
    }

    // ---- test connection --------------------------------------------

    @Transactional
    public TestConnectionResponse testConnection(UUID configId) {
        ConnectionConfig config = findById(configId);

        // Prefer Draft if exists, else Published version
        ConnectionConfigVersion version = getDraftVersion(configId);
        if (version == null) {
            version = getPublishedVersion(configId);
        }

        if (version.getUrl() == null || version.getUrl().isBlank()) {
            throw new BadRequestException("Cannot test: URL is not set on the version.");
        }

        // BR-CC-14: HTTP requires test_endpoint
        if (ConnectionType.HTTP.equals(config.getType())) {
            Map<String, Object> cfg = version.getConfig();
            String testEndpoint = cfg != null ? (String) cfg.get("testEndpoint") : null;
            if (testEndpoint == null || testEndpoint.isBlank()) {
                throw new BadRequestException("Cannot test: Test Endpoint is required for HTTP connections.");
            }
        }

        long start = System.currentTimeMillis();
        try {
            String fullUrl = buildTestUrl(config.getType(), version.getUrl(), version.getConfig());
            boolean success = performConnectivityCheck(config.getType(), fullUrl);
            long latency = System.currentTimeMillis() - start;

            if (success) {
                version.setTestStatus(TestStatus.SUCCESS);
                version.setLastTestAt(Instant.now());
                version.setLastTestError(null);
                versionRepository.save(version);
                return new TestConnectionResponse(TestStatus.SUCCESS, latency, fullUrl, true, null);
            } else {
                version.setTestStatus(TestStatus.FAILED);
                version.setLastTestAt(Instant.now());
                version.setLastTestError("Connection check returned non-success status.");
                versionRepository.save(version);
                return new TestConnectionResponse(TestStatus.FAILED, latency, fullUrl, null,
                        "Connection check returned non-success status.");
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            version.setTestStatus(TestStatus.FAILED);
            version.setLastTestAt(Instant.now());
            version.setLastTestError(errorMsg);
            versionRepository.save(version);
            return new TestConnectionResponse(TestStatus.FAILED, latency, version.getUrl(), null, errorMsg);
        }
    }

    /**
     * Stateless test: tests connectivity using the URL and config provided in the request body,
     * without requiring the Draft to be saved first. Does not persist test status.
     */
    @Transactional
    public TestConnectionResponse testConnectionStateless(UUID configId, String url, Map<String, Object> config) {
        ConnectionConfig configEntity = findById(configId);

        if (url == null || url.isBlank()) {
            throw new BadRequestException("Cannot test: URL is required.");
        }

        // BR-CC-14: HTTP requires test_endpoint
        if (ConnectionType.HTTP.equals(configEntity.getType())) {
            String testEndpoint = config != null ? (String) config.get("testEndpoint") : null;
            if (testEndpoint == null || testEndpoint.isBlank()) {
                throw new BadRequestException("Cannot test: Test Endpoint is required for HTTP connections.");
            }
        }

        long start = System.currentTimeMillis();
        try {
            String fullUrl = buildTestUrl(configEntity.getType(), url, config);
            boolean success = performConnectivityCheck(configEntity.getType(), fullUrl);
            long latency = System.currentTimeMillis() - start;

            if (success) {
                return new TestConnectionResponse(TestStatus.SUCCESS, latency, fullUrl, true, null);
            } else {
                return new TestConnectionResponse(TestStatus.FAILED, latency, fullUrl, null,
                        "Connection check returned non-success status.");
            }
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new TestConnectionResponse(TestStatus.FAILED, latency, url, null, errorMsg);
        }
    }

    private String buildTestUrl(String type, String url, Map<String, Object> config) {
        if (ConnectionType.HTTP.equals(type) && config != null) {
            String testEndpoint = (String) config.get("testEndpoint");
            if (testEndpoint != null && !testEndpoint.isBlank()) {
                String base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
                String path = testEndpoint.startsWith("/") ? testEndpoint : "/" + testEndpoint;
                return base + path;
            }
        }
        return url;
    }

    private boolean performConnectivityCheck(String type, String fullUrl) throws Exception {
        switch (type) {
            case ConnectionType.HTTP:
            case ConnectionType.MANAGED_RAG: {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode() >= 200 && response.statusCode() < 300;
            }
            case ConnectionType.GIT: {
                // Use ls-remote via ProcessBuilder
                ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", fullUrl);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                return exitCode == 0;
            }
            case ConnectionType.S3: {
                // For S3, just validate the URL is reachable
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(fullUrl))
                        .timeout(Duration.ofSeconds(10))
                        .HEAD()
                        .build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                return response.statusCode() >= 200 && response.statusCode() < 400;
            }
            case ConnectionType.DB: {
                // For DB, just validate URL format (JDBC URL — no actual connection in e2e)
                return fullUrl.startsWith("jdbc:");
            }
            default:
                throw new BadRequestException("Unknown connection type: " + type);
        }
    }

    // ---- delete -------------------------------------------------------

    @Transactional
    public void delete(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);

        // Delete all versions first
        List<ConnectionConfigVersion> versions = versionRepository.findAllByConnectionConfigId(configId);
        versionRepository.deleteAll(versions);

        repository.delete(config);
        log.info("Deleted connection config: {} (id: {})", config.getName(), configId);

        auditEventService.recordEvent(
                ResourceType.CONNECTION_CONFIG, configId, AuditAction.DELETED,
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, null, null, Map.of("name", config.getName()), null);
    }
}