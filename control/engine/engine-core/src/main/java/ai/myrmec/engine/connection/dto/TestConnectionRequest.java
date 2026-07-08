// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection.dto;

import java.util.Map;

/**
 * Request body for the stateless Test Connection endpoint (UC-018 Step 5).
 * Allows testing a connection without saving the Draft first.
 */
public record TestConnectionRequest(
        String url,
        Map<String, Object> config) {
}