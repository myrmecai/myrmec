// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection.dto;

import ai.myrmec.engine.connection.ConnectionConfigVersion;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ConnectionConfigVersionResponse(
        UUID id,
        UUID connectionConfigId,
        Integer versionNumber,
        UUID parentVersionId,
        String status,
        String url,
        Map<String, Object> config,
        String testStatus,
        Instant lastTestAt,
        String lastTestError,
        UUID draftOwnerId,
        Instant publishedAt,
        UUID publishedBy,
        Instant createdAt,
        Instant updatedAt) {

    public static ConnectionConfigVersionResponse from(ConnectionConfigVersion v) {
        return new ConnectionConfigVersionResponse(
                v.getId(),
                v.getConnectionConfigId(),
                v.getVersionNumber(),
                v.getParentVersionId(),
                v.getStatus(),
                v.getUrl(),
                v.getConfig(),
                v.getTestStatus(),
                v.getLastTestAt(),
                v.getLastTestError(),
                v.getDraftOwnerId(),
                v.getPublishedAt(),
                v.getPublishedBy(),
                v.getCreatedAt(),
                v.getUpdatedAt());
    }
}