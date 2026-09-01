package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.security.CurrentUser;
import ai.myrmec.engine.conversation.dispatch.ConversationTurnDispatcher;
import ai.myrmec.engine.conversation.dto.ApprovalDecisionRequest;
import ai.myrmec.engine.conversation.dto.ConversationEventResponse;
import ai.myrmec.engine.conversation.dto.ConversationMessageResponse;
import ai.myrmec.engine.conversation.dto.ConversationResponse;
import ai.myrmec.engine.conversation.dto.CreateConversationRequest;
import ai.myrmec.engine.conversation.dto.MessageFeedbackRequest;
import ai.myrmec.engine.conversation.dto.PinMessageRequest;
import ai.myrmec.engine.conversation.dto.PostUserMessageRequest;
import ai.myrmec.engine.conversation.dto.UpdateConversationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
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

    /** Upper bound for a single scrollback page, to cap query cost. */
    private static final int MAX_MESSAGE_PAGE_SIZE = 200;

    private final ConversationService conversationService;
    private final ConversationEventService conversationEventService;
    private final ConversationTurnDispatcher turnDispatcher;
    private final ApprovalDecisionDispatcher approvalDecisionDispatcher;
    private final ai.myrmec.engine.attachment.AttachmentService attachmentService;
    private final ConversationSummaryService conversationSummaryService;
    private final ai.myrmec.engine.agent.AgentHostService agentService;

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
                request.systemPromptOverride(),
                request.assistantId());
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

    @PatchMapping("/{id}")
    @Operation(summary = "Rename and/or archive a conversation (owner-only ⋯ menu actions)")
    @PreAuthorize("@conversationAccess.canOwn(#id, authentication)")
    public ResponseEntity<ConversationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateConversationRequest request) {
        Conversation updated = conversationService.updateConversation(
                id, request.title(), request.status());
        return ResponseEntity.ok(ConversationResponse.from(updated));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "List messages in a conversation in sequence order; "
            + "pass limit (and optional before cursor) to page scrollback")
    @PreAuthorize("@conversationAccess.canView(#id, authentication)")
    public ResponseEntity<List<ConversationMessageResponse>> messages(
            @PathVariable UUID id,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long before) {
        List<ConversationMessage> rows = (limit == null)
                ? conversationService.listMessages(id)
                : conversationService.listMessages(
                        id, Math.min(Math.max(limit, 1), MAX_MESSAGE_PAGE_SIZE), before);
        List<ConversationMessageResponse> body = rows.stream()
                .map(ConversationMessageResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}/events")
    @Operation(summary = "Replay the append-only lifecycle log for a conversation "
            + "(worker reserve/bind/release transitions), oldest first")
    @PreAuthorize("@conversationAccess.canView(#id, authentication)")
    public ResponseEntity<List<ConversationEventResponse>> events(@PathVariable UUID id) {
        List<ConversationEventResponse> body = conversationEventService.findByConversation(id).stream()
                .map(ConversationEventResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}/agent-availability")
    @Operation(summary = "Whether a worker is online to answer this conversation "
            + "(#88) — lets the chat UI warn before sending that a message will queue")
    @PreAuthorize("@conversationAccess.canView(#id, authentication)")
    public ResponseEntity<AgentAvailabilityResponse> agentAvailability(@PathVariable UUID id) {
        Conversation conversation = conversationService.findById(id);
        UUID hostId = conversation.getAgentId();
        if (hostId == null) {
            return ResponseEntity.ok(new AgentAvailabilityResponse(false, 0, 0));
        }
        int connected = agentService.countConnectedInstances(hostId);
        int idle = agentService.countOnlineInstances(hostId);
        return ResponseEntity.ok(new AgentAvailabilityResponse(connected > 0, connected, idle));
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
        // #103 — bind any freshly uploaded, scan-clean attachments to this turn
        // before dispatch so the agent receives them with the message.
        attachmentService.bindUnboundToMessage(id, saved.getId(), userId);
        // Phase 6d — fire-and-forget dispatch to an idle agent instance.
        // Failures (no idle agent, no pinned agentId, etc.) are logged by
        // the dispatcher; the REST response still reports the USER row was
        // saved so the UI can render it optimistically.
        // QuotaExceededException is NOT caught here — it propagates to the
        // GlobalExceptionHandler which maps it to HTTP 429 + Retry-After,
        // so the UI's quota-exceeded banner interceptor fires.
        try {
            turnDispatcher.dispatch(id);
        } catch (ai.myrmec.engine._system.exception.QuotaExceededException e) {
            throw e; // re-throw — let GlobalExceptionHandler map to 429
        } catch (Exception e) {
            log.warn("Turn dispatch threw for conversation {}: {}", id, e.getMessage(), e);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConversationMessageResponse.from(saved));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel the in-flight assistant turn for a conversation")
    @PreAuthorize("@conversationAccess.canEdit(#id, authentication)")
    public ResponseEntity<Map<String, Boolean>> cancelTurn(@PathVariable UUID id) {
        boolean delivered = turnDispatcher.cancel(id);
        return ResponseEntity.ok(Map.of("delivered", delivered));
    }

    @PostMapping("/{id}/summarise")
    @Operation(summary = "Explicitly summarise the conversation so far (#8), "
            + "folding the earlier turns into a running summary for handoff to a "
            + "fresh assistant. Best-effort: returns dispatched=false when a "
            + "summary is already in flight or there is nothing new to fold.")
    @PreAuthorize("@conversationAccess.canEdit(#id, authentication)")
    public ResponseEntity<Map<String, Boolean>> summariseNow(@PathVariable UUID id) {
        boolean dispatched = conversationSummaryService.summariseNow(id);
        return ResponseEntity.ok(Map.of("dispatched", dispatched));
    }

    @PostMapping("/{id}/messages/{messageId}/edit")
    @Operation(summary = "Edit a prior USER turn and resend it, forking the "
            + "conversation from that point (#104b)")
    @PreAuthorize("@conversationAccess.canEdit(#id, authentication)")
    public ResponseEntity<ConversationMessageResponse> editAndResend(
            @PathVariable UUID id,
            @PathVariable UUID messageId,
            @Valid @RequestBody PostUserMessageRequest request,
            @CurrentUser UUID userId) {
        ConversationMessage replacement = conversationService.editUserMessageAndResend(
                id, messageId, request.content(), userId);
        dispatchQuietly(id);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ConversationMessageResponse.from(replacement));
    }

    @PostMapping("/{id}/messages/{messageId}/regenerate")
    @Operation(summary = "Regenerate an ASSISTANT answer, retaining the "
            + "superseded turn (#104b)")
    @PreAuthorize("@conversationAccess.canEdit(#id, authentication)")
    public ResponseEntity<ConversationMessageResponse> regenerate(
            @PathVariable UUID id,
            @PathVariable UUID messageId,
            @CurrentUser UUID userId) {
        ConversationMessage superseded = conversationService.regenerateAssistantMessage(
                id, messageId, userId);
        dispatchQuietly(id);
        return ResponseEntity.ok(ConversationMessageResponse.from(superseded));
    }

    /**
     * Fire-and-forget dispatch shared by post / edit / regenerate. Dispatch
     * failures (no idle agent, no pinned agentId, …) are logged by the
     * dispatcher; the REST mutation already persisted so the UI renders the
     * new branch regardless.
     */
    private void dispatchQuietly(UUID conversationId) {
        try {
            turnDispatcher.dispatch(conversationId);
        } catch (Exception e) {
            log.warn("Turn dispatch threw for conversation {}: {}",
                    conversationId, e.getMessage(), e);
        }
    }

    @PatchMapping("/{id}/messages/{messageId}/pin")
    @Operation(summary = "Pin or unpin a single message in a conversation")
    @PreAuthorize("@conversationAccess.canEdit(#id, authentication)")
    public ResponseEntity<ConversationMessageResponse> pinMessage(
            @PathVariable UUID id,
            @PathVariable UUID messageId,
            @Valid @RequestBody PinMessageRequest request) {
        ConversationMessage updated =
                conversationService.setMessagePinned(id, messageId, request.pinned());
        return ResponseEntity.ok(ConversationMessageResponse.from(updated));
    }

    @PatchMapping("/{id}/messages/{messageId}/feedback")
    @Operation(summary = "Rate (\uD83D\uDC4D / \uD83D\uDC4E) or clear feedback on an ASSISTANT message")
    @PreAuthorize("@conversationAccess.canView(#id, authentication)")
    public ResponseEntity<ConversationMessageResponse> rateMessage(
            @PathVariable UUID id,
            @PathVariable UUID messageId,
            @Valid @RequestBody MessageFeedbackRequest request,
            @CurrentUser UUID userId) {
        ConversationMessage updated = conversationService.setMessageFeedback(
                id, messageId, request.rating(), request.reason(), userId);
        return ResponseEntity.ok(ConversationMessageResponse.from(updated));
    }

    @GetMapping(value = "/{id}/export", produces = "text/markdown")
    @Operation(summary = "Export a conversation transcript as Markdown (audited)")
    @PreAuthorize("@conversationAccess.canView(#id, authentication)")
    public ResponseEntity<String> exportConversation(
            @PathVariable UUID id,
            @CurrentUser UUID userId) {
        String markdown = conversationService.exportMarkdown(id, userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"conversation-" + id + ".md\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(markdown);
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
        // Phase 7c — best-effort push to the conversation's pinned
        // agent so the SDK can resolve its blocking request_approval()
        // call. Decision is already persisted; dispatcher failures are
        // logged but don't fail the request.
        try {
            approvalDecisionDispatcher.dispatch(id, result);
        } catch (Exception e) {
            log.warn("approval.decision dispatch threw for conv {} msg {}: {}",
                    id, messageId, e.getMessage(), e);
        }
        // Return both rows so the UI can refresh the request card AND the
        // appended response row in one network call.
        List<ConversationMessageResponse> out = List.of(
                ConversationMessageResponse.from(result.request()),
                ConversationMessageResponse.from(result.response()));
        return ResponseEntity.ok(out);
    }
}
