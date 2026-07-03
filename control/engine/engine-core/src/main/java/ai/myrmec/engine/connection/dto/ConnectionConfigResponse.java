// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection.dto;

import ai.myrmec.engine.connection.ConnectionConfig;

import java.time.Instant;
import java.util.UUID;

public record ConnectionConfigResponse(
        UUID id,
        String scope,
        UUID projectId,
        String name,
        String description,
        String type,
        String status,
        UUID credentialSecretId,
        UUID currentVersionId,
        Instant publishedAt,
        UUID publishedBy,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt,
        UUID updatedBy) {

    public static ConnectionConfigResponse from(ConnectionConfig c) {
        return new ConnectionConfigResponse(
                c.getId(),
                c.getScope(),
                c.getProjectId(),
                c.getName(),
                c.getDescription(),
                c.getType(),
                c.getStatus(),
                c.getCredentialSecretId(),
                c.getCurrentVersionId(),
                c.getPublishedAt(),
                c.getPublishedBy(),
                c.getCreatedBy(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.getUpdatedBy());
    }
}