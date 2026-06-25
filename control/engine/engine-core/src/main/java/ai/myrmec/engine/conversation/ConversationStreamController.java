package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.security.ConversationAccessEvaluator;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.conversation.stream.ConversationStreamBroker;
import ai.myrmec.engine.conversation.stream.SseConversationSubscriber;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * User-facing live conversation stream over Server-Sent Events.
 *
 * <p>Replaces the former WebSocket transport
 * ({@code UserConversationWebSocketHandler}) per the locked client-facing
 * transport standard (ARCHITECTURE.md → <em>Client-Facing Real-Time
 * Transport</em>): conversation streaming is push-only (server → client),
 * so SSE is the right fit and the WebSocket tier is reserved for the
 * genuinely bidirectional agent↔engine sockets.</p>
 *
 * <p>On connect the endpoint replays the newest page of durable history
 * (so a late joiner sees prior turns) and then subscribes the response to
 * the {@link ConversationStreamBroker}, draining live
 * {@code message.delta} / {@code message.complete} / {@code task.cancelled}
 * / approval frames as they are produced. Client→server actions (send a
 * turn, cancel, regenerate) are discrete REST {@code POST}s on other
 * endpoints — this stream carries no inbound traffic.</p>
 *
 * <p>Authentication mirrors the old WebSocket handshake: the browser
 * {@code EventSource} API cannot set an {@code Authorization} header, so
 * the access token arrives as a {@code ?token=} query parameter and is
 * validated here (signature/expiry, user token, active user, viewer ACL).
 * The path is {@code permitAll} in {@code SecurityConfig} precisely
 * because this method performs the check itself.</p>
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@Slf4j
public class ConversationStreamController {

    /**
     * Newest-N transcript rows replayed to a late joiner on connect. Older
     * turns are pulled on demand via {@code GET /messages?limit=&before=}
     * (scrollback pagination) so a long conversation does not flood the
     * stream at connect time.
     */
    private static final int HISTORY_REPLAY_LIMIT = 50;

    /** 30 minutes — matches the workflow-log SSE stream. */
    private static final long STREAM_TIMEOUT_MS = 30 * 60 * 1000L;

    private final ConversationStreamBroker broker;
    private final ConversationService conversationService;
    private final ObjectMapper objectMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final ConversationAccessEvaluator conversationAccess;

    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id,
                             @RequestParam(value = "token", required = false) String token) {
        authorize(id, token);

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        SseConversationSubscriber subscriber = new SseConversationSubscriber(emitter);

        // Replay durable history once so a late joiner sees prior turns
        // before live streaming kicks in, then subscribe to the broker.
        // Each row goes out as a "history.message" envelope to keep it
        // distinguishable from live message.delta / message.complete frames.
        try {
            List<ConversationMessage> messages =
                    conversationService.listMessages(id, HISTORY_REPLAY_LIMIT, null);
            for (ConversationMessage msg : messages) {
                subscriber.send(objectMapper.writeValueAsString(buildHistoryEnvelope(msg)));
            }
        } catch (IOException e) {
            log.debug("Conversation SSE client disconnected during history replay: {}",
                    e.getMessage());
            emitter.completeWithError(e);
            return emitter;
        }

        broker.subscribe(id, subscriber);

        emitter.onCompletion(() -> {
            subscriber.markClosed();
            broker.unsubscribe(id, subscriber);
        });
        emitter.onTimeout(() -> {
            subscriber.markClosed();
            broker.unsubscribe(id, subscriber);
            emitter.complete();
        });
        emitter.onError(e -> {
            subscriber.markClosed();
            broker.unsubscribe(id, subscriber);
        });

        // Periodic heartbeat keeps idle proxies from dropping the stream.
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event()
                        .name("heartbeat")
                        .data(Instant.now().toString()));
            } catch (Exception e) {
                heartbeat.shutdown();
            }
        }, 30, 30, TimeUnit.SECONDS);
        emitter.onCompletion(heartbeat::shutdown);
        emitter.onTimeout(heartbeat::shutdown);

        log.debug("Conversation SSE client connected to conversation {}", id);
        return emitter;
    }

    /**
     * Validate the {@code ?token=} access token and viewer ACL — same
     * checks the retired WebSocket handshake interceptor performed.
     */
    private void authorize(UUID conversationId, String token) {
        if (token == null || token.isBlank()
                || !jwtTokenProvider.validateAccessToken(token)
                || !jwtTokenProvider.isUserToken(token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid token");
        }

        UUID userId = jwtTokenProvider.getSubjectId(token);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User missing or inactive");
        }

        UserPrincipal principal = new UserPrincipal(
                user.getId(), user.getName(), user.getEmail(), jwtTokenProvider.getRoles(token));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, Collections.emptyList());
        if (!conversationAccess.canView(conversationId, auth)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No view access on conversation");
        }
    }

    private Map<String, Object> buildHistoryEnvelope(ConversationMessage msg) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", msg.getConversationId().toString());
        payload.put("messageId", msg.getId().toString());
        payload.put("sequenceNo", msg.getSequenceNo());
        payload.put("role", msg.getRole() == null ? null : msg.getRole().name());
        payload.put("content", msg.getContent());
        if (msg.getAuthorUserId() != null) {
            payload.put("authorUserId", msg.getAuthorUserId().toString());
        }
        if (msg.getAuthorAgentId() != null) {
            payload.put("authorAgentId", msg.getAuthorAgentId().toString());
        }
        if (msg.getModelCode() != null) {
            payload.put("modelCode", msg.getModelCode());
        }
        if (msg.getTokenCount() != null) {
            payload.put("tokenCount", msg.getTokenCount());
        }
        payload.put("pinned", msg.isPinned());
        if (msg.getCreatedAt() != null) {
            payload.put("createdAt", msg.getCreatedAt().toString());
        }

        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", "history.message");
        envelope.put("payload", payload);
        return envelope;
    }
}
