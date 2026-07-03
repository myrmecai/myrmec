// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection.dto;

import jakarta.validation.constraints.Size;

import java.util.Map;

public record UpdateDraftRequest(
        @Size(max = 2000) String url,
        Map<String, Object> config) {
}