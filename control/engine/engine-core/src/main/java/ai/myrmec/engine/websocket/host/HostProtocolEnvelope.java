// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The wire envelope for every frame on the host-control socket (§4.3).
 * Ordering comes from state and {@code sequence}, never from {@code sentAt}.
 * Unknown optional fields are ignored; unknown message types and unsupported
 * versions produce {@code protocol.error}.
 */
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class HostProtocolEnvelope {

    private int protocolVersion;
    private String messageId;
    private String type;
    private Instant sentAt;
    /** Response frames only: the messageId of the command being answered. */
    private String correlationId;
    /** Required on frames after host.opened; must match the connection. */
    private UUID hostInstanceId;
    private UUID sessionId;
    private UUID executionId;
    private Integer sequence;
    private JsonNode payload;

    /** Parse and bind a text frame. Throws JacksonException on malformed JSON. */
    public static HostProtocolEnvelope parse(ObjectMapper mapper, String text) throws com.fasterxml.jackson.core.JacksonException {
        return mapper.readValue(text, HostProtocolEnvelope.class);
    }

    /**
     * Boundary validation (§14). Returns the error code, or null when the
     * envelope is structurally valid. State-level validation (type known,
     * identity match) is the handler's job — this checks shape only.
     */
    public String validate() {
        if (messageId == null || messageId.isBlank()) {
            return HostProtocol.INVALID_MESSAGE;
        }
        if (type == null || type.isBlank()) {
            return HostProtocol.INVALID_MESSAGE;
        }
        if (payload == null) {
            return HostProtocol.INVALID_MESSAGE;
        }
        if (protocolVersion != HostProtocol.SUPPORTED_VERSION) {
            return HostProtocol.UNSUPPORTED_VERSION;
        }
        return null;
    }

    /**
     * Build an outgoing frame: fresh messageId + sentAt, correlationId set to
     * the answered command, payload rendered from any Jackson-serializable
     * object or a raw Map.
     */
    public static HostProtocolEnvelope reply(String type, String correlationId,
                                             Object payloadData, ObjectMapper mapper) {
        HostProtocolEnvelope reply = new HostProtocolEnvelope();
        reply.setProtocolVersion(HostProtocol.SUPPORTED_VERSION);
        reply.setMessageId(UUID.randomUUID() + "-" + ThreadLocalRandom.current().nextInt(1000, 9999));
        reply.setType(type);
        reply.setSentAt(Instant.now());
        reply.setCorrelationId(correlationId);
        reply.setPayload(payloadData instanceof JsonNode node
                ? node : mapper.valueToTree(payloadData == null ? Map.of() : payloadData));
        return reply;
    }
}
