// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine._system.common.AuditReason;
import ai.myrmec.engine._system.common.DomainConstants.AuditAction;
import ai.myrmec.engine._system.common.DomainConstants.EntityStatus;
import ai.myrmec.engine._system.common.DomainConstants.Scope;
import ai.myrmec.engine._system.common.ResourceType;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.audit.AuditEventService;
import ai.myrmec.engine.governance.GovernancePolicyEnforcer;
import ai.myrmec.engine.governance.GovernanceScope;
import ai.myrmec.engine.governance.ProductFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataFeedService {

    private final DataFeedRepository repository;
    private final AuditEventService auditEventService;
    private final GovernancePolicyEnforcer governanceEnforcer;

    @Transactional(readOnly = true)
    public List<DataFeed> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull(Scope.ORGANIZATION);
    }

    @Transactional(readOnly = true)
    public DataFeed findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("DataFeed", id));
    }

    @Transactional
    public DataFeed create(String scope, UUID projectId, String name, String description,
                            UUID providerVersionId, String datasetName,
                            UUID connectionConfigId, Map<String, Object> connectionDetails,
                            String syncSchedule, UUID actorId, String actorDisplayName) {
        // Governance: DATA_FEEDS — gate feed source type by profile.
        // The feed type discriminator lives in connectionDetails["type"]
        // (e.g. GIT, WEB_CRAWL, CONFLUENCE, JIRA, NOTION, S3, DB_SCHEMA).
        // When the type is absent (legacy/unclassified feeds) we skip the
        // gate — governance only constrains classified feed types.
        String feedType = connectionDetails != null
                ? (String) connectionDetails.get("type")
                : null;
        if (feedType != null && !feedType.isBlank()) {
            governanceEnforcer.assertAllowed(
                projectId != null ? GovernanceScope.ofProject(projectId) : GovernanceScope.orgScope(),
                ProductFeature.DATA_FEEDS,
                feedType);
        }

        DataFeed feed = new DataFeed();
        feed.setScope(scope);
        feed.setProjectId(projectId);
        feed.setName(name);
        feed.setDescription(description);
        feed.setStatus(EntityStatus.ACTIVE);
        feed.setProviderVersionId(providerVersionId);
        feed.setDatasetName(datasetName);
        feed.setConnectionConfigId(connectionConfigId);
        feed.setConnectionDetails(connectionDetails);
        feed.setSyncSchedule(syncSchedule);
        feed.setSyncStatus("NEVER");
        feed.setCreatedBy(actorId);

        DataFeed saved = repository.save(feed);
        log.info("Created data feed: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent(ResourceType.DATA_FEED, saved.getId(), AuditAction.CREATED,
                scope, projectId, actorId, actorDisplayName,
                null, null, null, Map.of("name", name, "datasetName", datasetName), null);

        return saved;
    }

    @Transactional
    public DataFeed triggerSync(UUID id, UUID actorId, String actorDisplayName) {
        DataFeed feed = findById(id);
        feed.setSyncStatus("SYNCING");
        feed.setLastSyncAt(Instant.now());
        DataFeed saved = repository.save(feed);
        log.info("Triggered sync for data feed: {}", id);
        // Actual sync logic would be async — this just marks the status
        return saved;
    }

    @Transactional
    public DataFeed disable(UUID id, UUID actorId, String actorDisplayName) {
        DataFeed feed = findById(id);
        feed.setStatus(EntityStatus.DISABLED);
        DataFeed saved = repository.save(feed);
        auditEventService.recordEvent(ResourceType.DATA_FEED, id, EntityStatus.DISABLED,
                feed.getScope(), feed.getProjectId(), actorId, actorDisplayName,
                null, AuditReason.ADMIN_DISABLED, null, null, null);
        return saved;
    }
}