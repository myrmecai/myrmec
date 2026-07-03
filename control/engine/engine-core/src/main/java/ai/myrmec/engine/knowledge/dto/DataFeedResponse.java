// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import ai.myrmec.engine.knowledge.DataFeed;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DataFeedResponse(
        UUID id, String scope, UUID projectId, String name, String description,
        String status, UUID providerVersionId, String datasetName,
        UUID connectionConfigId, Map<String, Object> connectionDetails,
        String syncSchedule, String syncStatus, Instant lastSyncAt,
        Integer chunkCount, String errorMessage, UUID createdBy,
        Instant createdAt, Instant updatedAt, UUID updatedBy) {

    public static DataFeedResponse from(DataFeed f) {
        return new DataFeedResponse(f.getId(), f.getScope(), f.getProjectId(), f.getName(),
                f.getDescription(), f.getStatus(), f.getProviderVersionId(), f.getDatasetName(),
                f.getConnectionConfigId(), f.getConnectionDetails(), f.getSyncSchedule(),
                f.getSyncStatus(), f.getLastSyncAt(), f.getChunkCount(), f.getErrorMessage(),
                f.getCreatedBy(), f.getCreatedAt(), f.getUpdatedAt(), f.getUpdatedBy());
    }
}