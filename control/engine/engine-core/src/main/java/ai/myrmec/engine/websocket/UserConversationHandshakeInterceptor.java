package ai.myrmec.engine.websocket;

import ai.myrmec.engine._system.security.ConversationAccessEvaluator;
import ai.myrmec.engine._system.security.JwtTokenProvider;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates the user-facing handshake for
 * {@code /api/v1/conversations/{id}/stream}. Token arrives as a
 * {@code ?token=} query parameter because the browser WebSocket API
 * doesn't let scripts set an {@code Authorization} header.
 *
 * <p>Approval steps:</p>
 * <ol>
 *   <li>Validate JWT signature/expiry, confirm it's a user token.</li>
 *   <li>Load the user — same {@link UserRepository} lookup the HTTP
 *       filter performs, so a deleted user can't reuse a still-valid
 *       JWT after sign-out.</li>
 *   <li>Confirm the user has at least viewer access on this conversation
 *       via {@link ConversationAccessEvaluator}.</li>
 * </ol>
 *
 * <p>On success the conversationId + user id are stashed in session
 * attributes so {@link UserConversationWebSocketHandler} can subscribe
 * to the broker without re-parsing the URI.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserConversationHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_CONVERSATION_ID = "conversationId";
    public static final String ATTR_USER_ID = "userId";

    private static final Pattern PATH_PATTERN = Pattern.compile(
            "^/api/v1/conversations/([0-9a-fA-F-]{36})/stream/?$");

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final ConversationAccessEvaluator conversationAccess;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 1. Conversation id from URL.
        Matcher m = PATH_PATTERN.matcher(request.getURI().getPath());
        if (!m.matches()) {
            log.warn("User WS handshake rejected: path does not match expected pattern: {}",
                    request.getURI().getPath());
            return false;
        }
        UUID conversationId = UUID.fromString(m.group(1));

        // 2. Token from query string.
        String token = extractToken(request);
        if (token == null || token.isBlank()) {
            log.warn("User WS handshake rejected for conversation {}: missing token", conversationId);
            return false;
        }
        if (!jwtTokenProvider.validateAccessToken(token) || !jwtTokenProvider.isUserToken(token)) {
            log.warn("User WS handshake rejected for conversation {}: invalid/non-user token", conversationId);
            return false;
        }

        UUID userId = jwtTokenProvider.getSubjectId(token);
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getIsActive())) {
            log.warn("User WS handshake rejected for conversation {}: user {} missing/inactive",
                    conversationId, userId);
            return false;
        }

        // 3. ACL check — reuse the same evaluator the REST endpoints use.
        UserPrincipal principal = new UserPrincipal(
                user.getId(), user.getName(), user.getEmail(), jwtTokenProvider.getRoles(token));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, Collections.emptyList());
        if (!conversationAccess.canView(conversationId, auth)) {
            log.warn("User WS handshake rejected for conversation {}: user {} lacks view access",
                    conversationId, userId);
            return false;
        }

        attributes.put(ATTR_CONVERSATION_ID, conversationId);
        attributes.put(ATTR_USER_ID, userId);
        log.debug("User WS handshake approved for user {} on conversation {}", userId, conversationId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception != null) {
            log.error("User WS handshake error: {}", exception.getMessage());
        }
    }

    private String extractToken(ServerHttpRequest request) {
        try {
            return UriComponentsBuilder.fromUri(request.getURI())
                    .build()
                    .getQueryParams()
                    .getFirst("token");
        } catch (Exception e) {
            log.debug("Failed to extract token from request: {}", e.getMessage());
            return null;
        }
    }
}
