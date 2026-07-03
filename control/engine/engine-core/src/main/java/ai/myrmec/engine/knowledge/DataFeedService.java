// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

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

@Slf4j
@Service
@RequiredArgsConstructor
public class DataFeedService {

    private final DataFeedRepository repository;
    private final AuditEventService auditEventService;

    @Transactional(readOnly = true)
    public List<DataFeed> findAllOrgScoped() {
        return repository.findByScopeAndProjectIdIsNull("ORGANIZATION");
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
        DataFeed feed = new DataFeed();
        feed.setScope(scope);
        feed.setProjectId(projectId);
        feed.setName(name);
        feed.setDescription(description);
        feed.setStatus("ACTIVE");
        feed.setProviderVersionId(providerVersionId);
        feed.setDatasetName(datasetName);
        feed.setConnectionConfigId(connectionConfigId);
        feed.setConnectionDetails(connectionDetails);
        feed.setSyncSchedule(syncSchedule);
        feed.setSyncStatus("NEVER");
        feed.setCreatedBy(actorId);

        DataFeed saved = repository.save(feed);
        log.info("Created data feed: {} (id: {})", name, saved.getId());

        auditEventService.recordEvent("data_feed", saved.getId(), "CREATED",
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
        feed.setStatus("DISABLED");
        DataFeed saved = repository.save(feed);
        auditEventService.recordEvent("data_feed", id, "DISABLED",
                feed.getScope(), feed.getProjectId(), actorId, actorDisplayName,
                null, "ADMIN_DISABLED", null, null, null);
        return saved;
    }
}