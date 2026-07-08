// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection.dto;

/**
 * Response from the Test Connection endpoint (UC-018 Step 5).
 * Status is SUCCESS or FAILED — a 200 is returned in both cases.
 * 502 is only returned if the engine cannot initiate the test.
 */
public record TestConnectionResponse(
        String status,
        long latencyMs,
        String endpoint,
        Boolean authenticated,
        String error) {
}