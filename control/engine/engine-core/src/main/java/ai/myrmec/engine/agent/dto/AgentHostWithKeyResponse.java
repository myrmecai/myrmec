// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent.dto;

import lombok.Builder;
import lombok.Data;

/**
 * Response DTO for newly created agent host with registration key.
 * The registration key is only returned once upon creation.
 */
@Data
@Builder
public class AgentHostWithKeyResponse {

    private AgentHostResponse agentHost;

    /**
     * The plaintext registration key.
     * Only returned on create - never stored or returned again.
     */
    private String registrationKey;
}
