// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine._system.security.AgentPrincipal;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.conversation.ConversationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * Agent-scoped attachment fetch surface for conversation turns (#103).
 *
 * <p>Workers use this endpoint when a descriptor's inline text is omitted by
 * size and they must fetch attachment bytes on demand with their AGENT JWT.</p>
 */
@RestController
@RequestMapping("/api/v1/agent/conversations/{conversationId}/attachments")
@RequiredArgsConstructor
@Tag(name = "Agent Attachments", description = "Agent-facing conversation attachment fetch (#103)")
public class AgentAttachmentController {

    private final AttachmentService attachmentService;
    private final ConversationRepository conversationRepository;
    private final AgentHostRepository agentHostRepository;

    @GetMapping("/{attachmentId}/content")
    @Operation(summary = "Download clean attachment bytes for an agent-bound conversation")
    public ResponseEntity<Resource> download(
            @PathVariable UUID conversationId,
            @PathVariable UUID attachmentId,
            @AuthenticationPrincipal AgentPrincipal principal) {
        ensureAgentCanRead(conversationId, principal);

        ConversationMessageAttachment row = attachmentService.require(conversationId, attachmentId);
        byte[] bytes = attachmentService.download(conversationId, attachmentId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(row.getFilename())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(row.getMediaType()))
                .body(new ByteArrayResource(bytes));
    }

    private void ensureAgentCanRead(UUID conversationId, AgentPrincipal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Missing authenticated agent principal");
        }

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Conversation", conversationId));

        UUID agentHostId = principal.getAgentId();
        if (!Objects.equals(conversation.getAgentId(), agentHostId)) {
            throw new AccessDeniedException("Agent is not bound to this conversation");
        }

        UUID agentProjectId = agentHostRepository.findById(agentHostId)
                .map(AgentHost::getProjectId)
                .orElse(null);
        if (!Objects.equals(agentProjectId, conversation.getProjectId())) {
            throw new AccessDeniedException("Agent project scope does not match conversation project");
        }
    }
}
