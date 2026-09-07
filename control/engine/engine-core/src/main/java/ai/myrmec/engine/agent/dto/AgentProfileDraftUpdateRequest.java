// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.agent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * Request DTO for editing an agent profile's open DRAFT (the Draft →
 * Publish lifecycle, design §16.1). Published versions are immutable —
 * every field here applies only to a DRAFT row. Null fields keep the
 * draft's current value (PATCH semantics, mirroring the Assistant
 * UpdateDraftRequest pattern).
 */
@Data
public class AgentProfileDraftUpdateRequest {

    /**
     * Software/hardware capabilities this profile supports.
     */
    private List<String> capabilities;

    /**
     * Tool codes from the tools registry to assign to this profile.
     */
    private Set<String> toolCodes;

    /**
     * System prompt for agents running this profile.
     */
    @Size(max = 32000, message = "System prompt must be at most 32000 characters")
    private String systemPrompt;

    /**
     * Default model code for agents using this profile.
     */
    @Size(max = 50, message = "Default model must be at most 50 characters")
    private String defaultModel;

    /**
     * Conversation participation mode (ONE_SHOT or CONVERSATIONAL).
     */
    private String interactionMode;
}