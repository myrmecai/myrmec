// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.websocket.host.payload.ChannelOpenPayload;
import ai.myrmec.engine.websocket.host.payload.ChannelOpenedPayload;
import ai.myrmec.engine.websocket.host.payload.ProtocolErrorPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

/**
 * The optional dedicated session-channel socket (protocol §7.5). After
 * receiving {@code session.open} a host MAY open this endpoint and bind it
 * to one session with {@code channel.open}; the offer token (single-session,
 * short-lived, single-use, §15 rule 7 transport-binding-only) is consumed on
 * that first frame.
 *
 * <p>The handshake is the same HOST_JWT gate as the control socket; the
 * channel token rides the payload, NOT the auth. Execution traffic on the
 * bound socket takes the SAME inbound arm as the control socket (delegated);
 * control-only frames get INVALID_MESSAGE. Channel loss never closes the
 * session (§7.5): the registry drops the binding and the session keeps
 * riding the control socket.</p>
 */
@Slf4j
@Component
public class HostChannelWebSocketHandler extends TextWebSocketHandler {

    static final String ATTR_BOUND_SESSION_ID = "channelBoundSessionId";

    private final ObjectMapper objectMapper;
    private final ChannelTokenService channelTokenService;
    private final ChannelConnectionRegistry channelRegistry;
    private final HostControlWebSocketHandler controlHandler;
    private final SessionRepository sessionRepository;

    public HostChannelWebSocketHandler(ObjectMapper objectMapper,
                                       ChannelTokenService channelTokenService,
                                       ChannelConnectionRegistry channelRegistry,
                                       HostControlWebSocketHandler controlHandler,
                                       SessionRepository sessionRepository) {
        this.objectMapper = objectMapper;
        this.channelTokenService = channelTokenService;
        this.channelRegistry = channelRegistry;
        this.controlHandler = controlHandler;
        this.sessionRepository = sessionRepository;
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        final HostProtocolEnvelope envelope;
        try {
            envelope = HostProtocolEnvelope.parse(objectMapper, message.getPayload());
        } catch (Exception e) {
            log.warn("Malformed channel frame rejected: {}", e.getMessage());
            sendError(socket, null, HostProtocol.INVALID_MESSAGE,
                    "Frame is not a valid envelope");
            return;
        }
        String shapeError = envelope.validate();
        if (shapeError != null) {
            sendError(socket, envelope.getMessageId(), shapeError,
                    "Envelope failed boundary validation");
            return;
        }

        UUID boundSession = (UUID) socket.getAttributes().get(ATTR_BOUND_SESSION_ID);
        if (boundSession == null) {
            // First (binding) frame.
            if (!HostProtocol.CHANNEL_OPEN.equals(envelope.getType())) {
                sendError(socket, envelope.getMessageId(), HostProtocol.INVALID_STATE,
                        "The first frame on the channel socket must be channel.open");
                return;
            }
            bind(socket, envelope);
            return;
        }
        // Bound: execution traffic takes the SAME arm as the control socket;
        // everything else (session.*, host.*, another channel.open) is refused.
        controlHandler.handleChannelInbound(socket, envelope);
    }

    /** §7.5: consume the token, bind the socket, answer channel.opened. */
    private void bind(WebSocketSession socket, HostProtocolEnvelope envelope) {
        UUID authenticatedHostId = (UUID) socket.getAttributes()
                .get(HostControlHandshakeInterceptor.ATTR_HOST_ID);
        if (authenticatedHostId == null) {
            sendError(socket, envelope.getMessageId(), HostProtocol.IDENTITY_MISMATCH,
                    "Connection is not host-authenticated");
            return;
        }
        ChannelOpenPayload open;
        try {
            open = objectMapper.treeToValue(envelope.getPayload(), ChannelOpenPayload.class);
        } catch (Exception e) {
            sendError(socket, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "channel.open payload failed validation: " + e.getMessage());
            return;
        }
        if (open.sessionId() == null) {
            sendError(socket, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "sessionId is required");
            return;
        }
        String token = extractToken(envelope.getPayload());
        if (token == null) {
            sendError(socket, envelope.getMessageId(), HostProtocol.INVALID_MESSAGE,
                    "channel token is required");
            return;
        }
        // §15 rule 7: the token was minted for a specific (session, instance);
        // consume() enforces single-use, expiry, session binding, and that the
        // minting instance belongs to the handshake-authenticated host.
        ChannelTokenService.ConsumeResult result =
                channelTokenService.consume(token, open.sessionId(), authenticatedHostId);
        if (!result.ok()) {
            sendError(socket, envelope.getMessageId(), result.error(),
                    "channel.open rejected: invalid or misbound token");
            return;
        }
        // The minted binding, not the caller's claim, is authoritative. The
        // minting instance becomes the socket's connection identity so the
        // delegated control-handler arm sees the SAME identity shape it does
        // on the control socket (ATTR_HOST_INSTANCE_ID).
        socket.getAttributes().put(ATTR_BOUND_SESSION_ID, open.sessionId());
        socket.getAttributes().put(HostControlWebSocketHandler.ATTR_HOST_INSTANCE_ID,
                result.hostInstanceId());
        channelRegistry.register(open.sessionId(), socket);

        // The cursor is the engine's current highest contiguous sequence for
        // the session (the §12.3 replay resume point).
        long cursor = sessionRepository.findById(open.sessionId())
                .map(ai.myrmec.engine.inference.Session::getHighestContiguousSequence)
                .orElse(0L);
        log.info("Channel bound: session {} (resumeFrom {}, cursor {})",
                open.sessionId(), open.resumeFromSequence(), cursor);
        HostProtocolEnvelope reply = HostProtocolEnvelope.reply(
                HostProtocol.CHANNEL_OPENED, envelope.getMessageId(),
                new ChannelOpenedPayload(open.sessionId(), cursor), objectMapper);
        reply.setSessionId(open.sessionId());
        send(socket, reply);
    }

    /** The token is IN the payload, NOT the auth. */
    private String extractToken(JsonNode payload) {
        JsonNode tokenNode = payload.get("token");
        return tokenNode != null && !tokenNode.isNull() ? tokenNode.asText() : null;
    }

    private void sendError(WebSocketSession socket, String correlationId, String code,
                           String message) {
        send(socket, HostProtocolEnvelope.reply(HostProtocol.PROTOCOL_ERROR, correlationId,
                new ProtocolErrorPayload(code, message, false, correlationId,
                        "CONNECTION", null), objectMapper));
    }

    private void send(WebSocketSession socket, HostProtocolEnvelope envelope) {
        try {
            socket.sendMessage(new TextMessage(objectMapper.writeValueAsString(envelope)));
        } catch (IOException e) {
            log.warn("Failed sending channel frame: {}", e.getMessage());
        }
    }

    /** §7.5: channel loss NEVER closes the session — drop the binding, warn, move on. */
    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        UUID sessionId = (UUID) socket.getAttributes().get(ATTR_BOUND_SESSION_ID);
        if (sessionId != null) {
            channelRegistry.unregister(sessionId, socket);
            log.warn("Dedicated channel lost for session {} ({}): session unaffected — "
                    + "traffic continues on the control socket", sessionId, status);
        }
    }
}