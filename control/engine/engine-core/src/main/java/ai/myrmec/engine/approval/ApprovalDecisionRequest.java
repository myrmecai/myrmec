// SPDX-License-Identifier: Apache-2.0
// Copyright 2025 The Myrmec Authors

package ai.myrmec.engine.approval;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for submitting an approval decision.
 */
public record ApprovalDecisionRequest(
        @NotBlank(message = "Decision is required")
        String decision,
        
        String comment
) {}
