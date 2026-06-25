// SPDX-License-Identifier: Apache-2.0
// Copyright 2025 The Myrmec Authors

package ai.myrmec.engine.approval;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;

/**
 * Response DTO for approval decision submission.
 */
@Builder
@JsonDeserialize(builder = ApprovalDecisionResponse.ApprovalDecisionResponseBuilder.class)
public record ApprovalDecisionResponse(
        String source,
        String approvalId,
        String decision
) {
    @JsonPOJOBuilder(withPrefix = "")
    public static class ApprovalDecisionResponseBuilder {}
}
