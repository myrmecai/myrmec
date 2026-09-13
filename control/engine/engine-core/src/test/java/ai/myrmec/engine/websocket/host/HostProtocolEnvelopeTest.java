// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Envelope boundary validation (protocol §4.3/§14): required fields, version
 * negotiation, unknown-field tolerance. Ordering comes from state and
 * sequence, never sentAt.
 */
class HostProtocolEnvelopeTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void parsesFullFrameWithEveryField() throws JacksonException {
        String frame = """
                {
                  "protocolVersion": 1,
                  "messageId": "m-1",
                  "type": "host.open",
                  "sentAt": "2026-09-13T10:15:30.123Z",
                  "correlationId": "c-1",
                  "hostInstanceId": null,
                  "sequence": 4,
                  "payload": { "poolSize": 5 }
                }
                """;

        HostProtocolEnvelope envelope = HostProtocolEnvelope.parse(mapper, frame);

        assertThat(envelope.getProtocolVersion()).isEqualTo(1);
        assertThat(envelope.getMessageId()).isEqualTo("m-1");
        assertThat(envelope.getType()).isEqualTo("host.open");
        assertThat(envelope.getSentAt()).isNotNull();
        assertThat(envelope.getCorrelationId()).isEqualTo("c-1");
        assertThat(envelope.getSequence()).isEqualTo(4);
        assertThat(envelope.getPayload().path("poolSize").asInt()).isEqualTo(5);
        assertThat(envelope.validate()).isNull();
    }

    @Test
    void unknownOptionalFieldsAreIgnored() throws JacksonException {
        String frame = """
                { "protocolVersion": 1, "messageId": "m-2", "type": "host.heartbeat",
                  "sentAt": "2026-09-13T10:15:31.000Z",
                  "someFutureField": { "x": 1 }, "payload": {} }
                """;

        HostProtocolEnvelope envelope = HostProtocolEnvelope.parse(mapper, frame);

        assertThat(envelope.validate()).isNull();
    }

    @Test
    void missingMessageIdIsInvalidMessage() throws JacksonException {
        String frame = """
                { "protocolVersion": 1, "type": "host.open",
                  "sentAt": "2026-09-13T10:15:31.000Z", "payload": {} }
                """;

        assertThat(HostProtocolEnvelope.parse(mapper, frame).validate())
                .isEqualTo(HostProtocol.INVALID_MESSAGE);
    }

    @Test
    void missingTypeIsInvalidMessage() throws JacksonException {
        String frame = """
                { "protocolVersion": 1, "messageId": "m-3",
                  "sentAt": "2026-09-13T10:15:31.000Z", "payload": {} }
                """;

        assertThat(HostProtocolEnvelope.parse(mapper, frame).validate())
                .isEqualTo(HostProtocol.INVALID_MESSAGE);
    }

    @Test
    void missingPayloadIsInvalidMessage() throws JacksonException {
        String frame = """
                { "protocolVersion": 1, "messageId": "m-4", "type": "host.open",
                  "sentAt": "2026-09-13T10:15:31.000Z" }
                """;

        assertThat(HostProtocolEnvelope.parse(mapper, frame).validate())
                .isEqualTo(HostProtocol.INVALID_MESSAGE);
    }

    @Test
    void unsupportedVersionIsRejected() throws JacksonException {
        String frame = """
                { "protocolVersion": 99, "messageId": "m-5", "type": "host.open",
                  "sentAt": "2026-09-13T10:15:31.000Z", "payload": {} }
                """;

        assertThat(HostProtocolEnvelope.parse(mapper, frame).validate())
                .isEqualTo(HostProtocol.UNSUPPORTED_VERSION);
    }

    @Test
    void malformedJsonIsRejectedByParse() {
        String frame = "{ this is not json ";

        org.junit.jupiter.api.Assertions.assertThrows(
                com.fasterxml.jackson.core.JacksonException.class,
                () -> HostProtocolEnvelope.parse(mapper, frame));
    }

    @Test
    void replyBuildsCorrelatedEnvelope() {
        HostProtocolEnvelope reply = HostProtocolEnvelope.reply(
                HostProtocol.PROTOCOL_ERROR, "m-9", java.util.Map.of("code", "X"), mapper);

        assertThat(reply.getCorrelationId()).isEqualTo("m-9");
        assertThat(reply.getType()).isEqualTo("protocol.error");
        assertThat(reply.getPayload().path("code").asText()).isEqualTo("X");
        assertThat(reply.getMessageId()).isNotBlank();
        assertThat(reply.getSentAt()).isNotNull();
    }
}
