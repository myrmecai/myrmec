// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Response of {@code POST /api/v1/agent/auth/local/register}. Contains the
 * ephemeral agent definition, the agent instance ID, and a short-lived agent
 * credential pair that the local IDE extension uses to open the agent WebSocket.
 */
@Data
@Builder
public class LocalAgentRegisterResponse {

    private UUID agentId;
    private UUID instanceId;
    private String accessToken;
    private Instant accessTokenExpiresAt;
    private String refreshToken;
    private Instant refreshTokenExpiresAt;
}
