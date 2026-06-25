package ai.myrmec.engine.attachment;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.attachment.dto.AttachmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * REST surface for conversation attachments (#103). Uploads pass the scan
 * gate (#104) in the service before any bytes are stored. All endpoints are
 * scoped to a conversation and gated by {@code @conversationAccess}.
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/attachments")
@RequiredArgsConstructor
@Tag(name = "Attachments", description = "Conversation file/document attachments (#103)")
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping
    @Operation(summary = "Upload an attachment to a conversation (scanned before storage)")
    @PreAuthorize("@conversationAccess.canEdit(#conversationId, authentication)")
    public ResponseEntity<AttachmentResponse> upload(
            @PathVariable UUID conversationId,
            @RequestParam("file") MultipartFile file,
            @CurrentUser UUID userId) {
        ConversationMessageAttachment saved =
                attachmentService.upload(conversationId, file, userId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AttachmentResponse.from(saved));
    }

    @GetMapping
    @Operation(summary = "List a conversation's attachments")
    @PreAuthorize("@conversationAccess.canView(#conversationId, authentication)")
    public List<AttachmentResponse> list(@PathVariable UUID conversationId) {
        return attachmentService.list(conversationId).stream()
                .map(AttachmentResponse::from)
                .toList();
    }

    @GetMapping("/{attachmentId}/content")
    @Operation(summary = "Download the raw bytes of a clean attachment")
    @PreAuthorize("@conversationAccess.canView(#conversationId, authentication)")
    public ResponseEntity<Resource> download(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId) {
        ConversationMessageAttachment row =
                attachmentService.require(conversationId, attachmentId);
        byte[] bytes = attachmentService.download(conversationId, attachmentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(row.getFilename())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(row.getMediaType()))
                .body(new ByteArrayResource(bytes));
    }

    @DeleteMapping("/{attachmentId}")
    @Operation(summary = "Delete an attachment from a conversation")
    @PreAuthorize("@conversationAccess.canEdit(#conversationId, authentication)")
    public ResponseEntity<Void> delete(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId,
            @CurrentUser UUID userId) {
        attachmentService.delete(conversationId, attachmentId, userId);
        return ResponseEntity.noContent().build();
    }
}
