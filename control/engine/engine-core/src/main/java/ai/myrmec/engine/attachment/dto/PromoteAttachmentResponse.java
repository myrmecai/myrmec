// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment.dto;

import java.util.UUID;

/**
 * Result of a successful promote-to-KB action (#103-C): the id of the created
 * knowledge source and its ingestion status.
 */
public record PromoteAttachmentResponse(
        UUID knowledgeSourceId,
        String status
) {
}
