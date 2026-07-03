// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

public record CreateDataFeedRequest(
        @NotBlank @Size(max = 20) String scope,
        UUID projectId,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotBlank UUID providerVersionId,
        @NotBlank @Size(max = 200) String datasetName,
        UUID connectionConfigId,
        Map<String, Object> connectionDetails,
        @Size(max = 100) String syncSchedule) {
}