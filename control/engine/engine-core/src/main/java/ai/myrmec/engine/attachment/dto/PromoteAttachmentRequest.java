// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for promoting a conversation attachment into a knowledge base
 * (#103-C). {@code sourceName} is optional; when blank the engine names the
 * created source after the attachment filename.
 */
public record PromoteAttachmentRequest(
        @NotNull(message = "knowledgeBaseId is required") UUID knowledgeBaseId,
        String sourceName
) {
}
