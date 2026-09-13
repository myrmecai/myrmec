// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.auth.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request to register a local agent instance from an authenticated user's
 * IDE/extension host. The user's access token provides identity; this body
 * scopes the local agent to a project or to the system if projectId is null.
 */
@Data
public class LocalAgentRegisterRequest {

    /**
     * Project to bind the local agent to. If null, the agent is system-wide
     * and can only be dispatched to assistants/workflows without project scope.
     */
    private UUID projectId;

    /**
     * ID of the agent profile the local agent should adopt. If null, a default
     * local-agent profile is selected by the engine.
     */
    private UUID profileId;
}
