package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.conversation.dispatch.ConversationTurnDispatcher;
import ai.myrmec.engine.conversation.dto.ApprovalDecisionRequest;
import ai.myrmec.engine.conversation.dto.ConversationMessageResponse;
import ai.myrmec.engine.conversation.dto.ConversationResponse;
import ai.myrmec.engine.conversation.dto.CreateConversationRequest;
import ai.myrmec.engine.conversation.dto.PostUserMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * User-facing REST surface for conversational sessions.
 *
 * <p>Phase 6c-1 ships create + list-messages + post-user-message; the
 * agent reads inbound user messages by polling its existing task queue
 * (a separate dispatcher will land in Phase 6c-2 to push them through
 * the agent WebSocket). The WebSocket stream endpoint for live viewers
 * also lands in Phase 6c-2.</p>
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Conversations", description = "Conversational session management (Phase 6)")
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationTurnDispatcher turnDispatcher;

    @PostMapping
    @Operation(summary = "Create a new conversation under a project")
    @PreAuthorize("@projectAccess.canView(#request.projectId, authentication)")
    public ResponseEntity<ConversationResponse> create(
            @Valid @RequestBody CreateConversationRequest request,
            @CurrentUser UUID userId) {
        Conversation c = conversationService.createConversation(
                request.projectId(),
                userId,
                request.title(),
                request.agentId(),
                request.systemPromptOverride());
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.from(c));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a conversation by id")
    @PreAuthorize("@conversationAccess.canView(#id, authentication)")
    public ResponseEntity<ConversationResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ConversationResponse.from(conversationService.findById(id)));
    }

    @GetMapping
    @Operation(summary = "List conversations under a project (most recently updated first)")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public ResponseEntity<List<ConversationResponse>> listByProject(
            @RequestParam UUID projectId) {
        List<ConversationResponse> body = conversationService.listByProject(projectId).stream()
                .map(ConversationResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "List all messages in a conversation, in sequence order")
    @PreAuthorize("@conversationAccess.canView(#id, authentication)")
    public ResponseEntity<List<ConversationMessageResponse>> messages(@PathVariable UUID id) {
        List<ConversationMessageResponse> body = conversationService.listMessages(id).stream()
                .map(ConversationMessageResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/messages")
    @Operation(summary = "Append a USER message to a conversation")
    @PreAuthorize("@conversationAccess.canEdit(#id, authentication)")
    public ResponseEntity<ConversationMessageResponse> postUserMessage(
            @PathVariable UUID id,
            @Valid @RequestBody PostUserMessageRequest request,
            @CurrentUser UUID userId) {
        ConversationMessage saved = conversationService.appendMessage(
                id,
                ConversationMessage.Role.USER,
                request.content(),
                userId,
                null);
        // Phase 6d \u2014 fire-and-forget dispatch to an idle agent instance.
        // Failures (no idle agent, no pinned agentId, etc.) are logged by
        // the dispatcher; the REST response still reports the USER row was
        // saved so the UI can render it optimistically.
        try {
            turnDispatcher.dispatch(id);
        } catch (Exception e) {
            log.warn("Turn dispatch threw for conversation {}: {}", id, e.getMessage(), e);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConversationMessageResponse.from(saved));
    }

    @PostMapping("/{id}/approvals/{messageId}")
    @Operation(summary = "Submit a human decision (APPROVED / REJECTED) for an APPROVAL_REQUEST row")
    @PreAuthorize("@conversationAccess.canEdit(#id, authentication)")
    public ResponseEntity<List<ConversationMessageResponse>> submitApprovalDecision(
            @PathVariable UUID id,
            @PathVariable UUID messageId,
            @Valid @RequestBody ApprovalDecisionRequest body,
            @CurrentUser UUID userId) {
        ConversationService.ApprovalDecisionResult result =
                conversationService.submitApprovalDecision(
                        id, messageId, userId, body.decision(), body.comment());
        // Return both rows so the UI can refresh the request card AND the
        // appended response row in one network call.
        List<ConversationMessageResponse> out = List.of(
                ConversationMessageResponse.from(result.request()),
                ConversationMessageResponse.from(result.response()));
        return ResponseEntity.ok(out);
    }
}
