// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return repository.findByScopeAndProjectIdIsNull("ORGANIZATION");
    }

    @Transactional(readOnly = true)
    public List<ConnectionConfig> findAllProjectScoped(UUID projectId) {
        return repository.findByScopeAndProjectId("PROJECT", projectId);
    }

    @Transactional(readOnly = true)
    public ConnectionConfig findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("ConnectionConfig", id));
    }

    @Transactional(readOnly = true)
    public ConnectionConfigVersion getPublishedVersion(UUID configId) {
        return versionRepository.findByConnectionConfigIdAndStatus(configId, "PUBLISHED")
                .orElseThrow(() -> ResourceNotFoundException.of("ConnectionConfigVersion",
                        "connectionConfigId=" + configId + ", status=PUBLISHED"));
    }

    @Transactional(readOnly = true)
    public ConnectionConfigVersion getDraftVersion(UUID configId) {
        return versionRepository.findByConnectionConfigIdAndStatus(configId, "DRAFT")
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
        config.setStatus("INCOMPLETE");
        config.setCredentialSecretId(credentialSecretId);
        config.setCreatedBy(actorId);

        ConnectionConfig saved = repository.save(config);
        log.info("Created connection config: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent(
                "connection_config", saved.getId(), "CREATED",
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name, "type", type), null);

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
                .findByConnectionConfigIdAndStatus(configId, "PUBLISHED").orElse(null);

        int versionNumber = publishedVersion != null ? publishedVersion.getVersionNumber() + 1 : 1;

        ConnectionConfigVersion draft = new ConnectionConfigVersion();
        draft.setConnectionConfigId(configId);
        draft.setVersionNumber(versionNumber);
        draft.setParentVersionId(publishedVersion != null ? publishedVersion.getId() : null);
        draft.setStatus("DRAFT");
        draft.setDraftOwnerId(actorId);

        // Copy URL and config from published version if it exists
        if (publishedVersion != null) {
            draft.setUrl(publishedVersion.getUrl());
            draft.setConfig(publishedVersion.getConfig());
        }

        ConnectionConfigVersion saved = versionRepository.save(draft);
        log.info("Created draft version {} for connection config: {}", versionNumber, configId);

        auditEventService.recordEvent(
                "connection_config", configId, "DRAFT_CREATED",
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

        // Archive the current published version if one exists
        ConnectionConfigVersion currentPublished = versionRepository
                .findByConnectionConfigIdAndStatus(configId, "PUBLISHED").orElse(null);
        if (currentPublished != null) {
            currentPublished.setStatus("ARCHIVED");
            versionRepository.save(currentPublished);
        }

        // Publish the draft
        draft.setStatus("PUBLISHED");
        draft.setDraftOwnerId(null);
        draft.setPublishedAt(Instant.now());
        draft.setPublishedBy(actorId);
        ConnectionConfigVersion saved = versionRepository.save(draft);

        // Update parent
        config.setCurrentVersionId(saved.getId());
        config.setStatus("ACTIVE");
        config.setPublishedAt(Instant.now());
        config.setPublishedBy(actorId);
        repository.save(config);

        log.info("Published connection config version {} (id: {})", draft.getVersionNumber(), configId);

        auditEventService.recordEvent(
                "connection_config", configId, "PUBLISHED",
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
                "connection_config", configId, "DRAFT_DISCARDED",
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                draft.getId(), null, null, null, null);
    }

    // ---- lifecycle ---------------------------------------------------

    @Transactional
    public ConnectionConfig disable(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        config.setStatus("DISABLED");
        ConnectionConfig saved = repository.save(config);
        log.info("Disabled connection config: {}", configId);

        auditEventService.recordEvent(
                "connection_config", configId, "DISABLED",
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_DISABLED", null, null, null);

        return saved;
    }

    @Transactional
    public ConnectionConfig reenable(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        config.setStatus("ACTIVE");
        ConnectionConfig saved = repository.save(config);
        log.info("Re-enabled connection config: {}", configId);

        auditEventService.recordEvent(
                "connection_config", configId, "REENABLED",
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_REENABLED", null, null, null);

        return saved;
    }

    @Transactional
    public ConnectionConfig archive(UUID configId, UUID actorId, String actorDisplayName) {
        ConnectionConfig config = findById(configId);
        config.setStatus("ARCHIVED");
        ConnectionConfig saved = repository.save(config);
        log.info("Archived connection config: {}", configId);

        auditEventService.recordEvent(
                "connection_config", configId, "ARCHIVED",
                config.getScope(), config.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_ARCHIVED", null, null, null);

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
        }
        if (configJson != null) {
            draft.setConfig(configJson);
        }

        return versionRepository.save(draft);
    }
}