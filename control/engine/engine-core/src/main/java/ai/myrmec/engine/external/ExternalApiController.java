package ai.myrmec.engine.external;

import ai.myrmec.engine._system.security.CurrentServiceAccount;
import ai.myrmec.engine._system.security.ServiceAccountPrincipal;
import ai.myrmec.engine.conversation.Conversation;
import ai.myrmec.engine.external.dto.ExternalAssistantResponse;
import ai.myrmec.engine.external.dto.ExternalConversationResponse;
import ai.myrmec.engine.external.dto.ExternalMessageResponse;
import ai.myrmec.engine.external.dto.PostMessageRequest;
import ai.myrmec.engine.external.dto.StartConversationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * External API for conversational services (#95) — the machine-facing surface
 * for integrators. Every request is authenticated by the resource-server chain
 * ({@code ExternalApiSecurityConfig}) as a {@link ServiceAccountPrincipal};
 * assistant access is enforced by the #95 double gate (USE grant ∩
 * {@code EXTERNAL_API} reach) in {@link ExternalAssistantService}, and
 * conversation access is scoped to the calling service account in
 * {@link ExternalConversationService}.
 *
 * <p>The opaque caller-side end-user identity travels in the
 * {@code X-Myrmec-End-User-Ref} header and is required to open a conversation.</p>
 */
@RestController
@RequestMapping("/api/v1/external")
@RequiredArgsConstructor
@Tag(name = "External API", description = "Conversational services for external integrations (#95)")
public class ExternalApiController {

    static final String END_USER_REF_HEADER = "X-Myrmec-End-User-Ref";

    private final ExternalAssistantService assistantService;
    private final ExternalConversationService conversationService;

    // ==================== Assistant discovery ====================

    @GetMapping("/assistants")
    @Operation(summary = "List assistants this service account may use over the External API")
    public ResponseEntity<List<ExternalAssistantResponse>> listAssistants(
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        List<ExternalAssistantResponse> body = assistantService
                .listUsable(principal.getServiceAccountId(), principal.getProjectId()).stream()
                .map(ExternalAssistantResponse::of)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/assistants/{id}")
    @Operation(summary = "Get one usable assistant by id")
    public ResponseEntity<ExternalAssistantResponse> getAssistant(
            @PathVariable UUID id,
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        ExternalAssistantService.UsableAssistant usable = assistantService.requireUsable(
                principal.getServiceAccountId(), principal.getProjectId(), id);
        return ResponseEntity.ok(ExternalAssistantResponse.of(usable));
    }

    // ==================== Conversations ====================

    @PostMapping("/assistants/{id}/conversations")
    @Operation(summary = "Start a new conversation with an assistant for an external end user")
    public ResponseEntity<ExternalConversationResponse> startConversation(
            @PathVariable UUID id,
            @RequestHeader(name = END_USER_REF_HEADER, required = false) String endUserRef,
            @Valid @RequestBody(required = false) StartConversationRequest request,
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        String firstMessage = request == null ? null : request.firstMessage();
        Conversation conversation = conversationService.startConversation(principal, id, endUserRef, firstMessage);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ExternalConversationResponse.of(conversation));
    }

    @GetMapping("/conversations")
    @Operation(summary = "List conversations opened by this service account, "
            + "optionally filtered to one end-user reference")
    public ResponseEntity<List<ExternalConversationResponse>> listConversations(
            @RequestParam(name = "externalUserRef", required = false) String externalUserRef,
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        List<ExternalConversationResponse> body = conversationService
                .listConversations(principal, externalUserRef).stream()
                .map(ExternalConversationResponse::of)
                .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/conversations/{id}")
    @Operation(summary = "Get one owned conversation by id")
    public ResponseEntity<ExternalConversationResponse> getConversation(
            @PathVariable UUID id,
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        return ResponseEntity.ok(ExternalConversationResponse.of(conversationService.getConversation(principal, id)));
    }

    @PostMapping("/conversations/{id}/messages")
    @Operation(summary = "Post a user turn to a conversation")
    public ResponseEntity<ExternalMessageResponse> postMessage(
            @PathVariable UUID id,
            @Valid @RequestBody PostMessageRequest request,
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ExternalMessageResponse.of(
                        conversationService.postMessage(principal, id, request.content())));
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "List a conversation's messages, optionally only those newer "
            + "than the given sequence cursor (since)")
    public ResponseEntity<List<ExternalMessageResponse>> listMessages(
            @PathVariable UUID id,
            @RequestParam(name = "since", required = false) Long since,
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        List<ExternalMessageResponse> body = conversationService
                .listMessages(principal, id, since).stream()
                .map(ExternalMessageResponse::of)
                .toList();
        return ResponseEntity.ok(body);
    }

    @PostMapping("/conversations/{id}/close")
    @Operation(summary = "Close a conversation (idempotent)")
    public ResponseEntity<ExternalConversationResponse> closeConversation(
            @PathVariable UUID id,
            @CurrentServiceAccount ServiceAccountPrincipal principal) {
        return ResponseEntity.ok(ExternalConversationResponse.of(
                conversationService.closeConversation(principal, id)));
    }
}
