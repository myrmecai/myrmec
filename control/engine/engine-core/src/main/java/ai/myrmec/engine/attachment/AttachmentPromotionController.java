// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.attachment.dto.PromoteAttachmentRequest;
import ai.myrmec.engine.attachment.dto.PromoteAttachmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST surface for promote-to-KB (#103-C). Promotes a scan-clean conversation
 * attachment into a project knowledge base so its content becomes durable and
 * retrievable on later sessions.
 *
 * <p>Gated on project EDITOR (which covers EDITOR on any of the project's
 * knowledge bases) <em>and</em> conversation visibility, so only a session
 * owner / project editor can promote.</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/conversations/{conversationId}/attachments")
@RequiredArgsConstructor
@Tag(name = "Attachments", description = "Conversation file/document attachments (#103)")
public class AttachmentPromotionController {

    private final AttachmentPromotionService promotionService;

    @PostMapping("/{attachmentId}/promote")
    @Operation(summary = "Promote a scan-clean attachment into a project knowledge base")
    @PreAuthorize("@projectAccess.canEdit(#projectId, authentication) "
            + "and @conversationAccess.canView(#conversationId, authentication)")
    public ResponseEntity<PromoteAttachmentResponse> promote(
            @PathVariable UUID projectId,
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId,
            @Valid @RequestBody PromoteAttachmentRequest request,
            @CurrentUser UUID userId) {
        PromoteAttachmentResponse response = promotionService.promote(
                projectId, conversationId, attachmentId,
                request.knowledgeBaseId(), request.sourceName(), userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
