// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.ValueInstantiationException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Section 22 wire contract golden vectors (Task 1): every new interaction
 * payload record parses exactly the section 22.4-22.7 JSON shapes, rejects
 * malformed identities/revisions/enums/hold policies, and the current
 * capability + orchestration policy requirements fail closed at
 * {@code host.open} / {@code session.open} assembly.
 *
 * <p>Wire authority: protocol section 22.2 (capability/policy), section 22.3
 * (catalogue), section 22.4 (control), section 22.6 (interaction), section 22.7
 * (proposals). Payload records live in the host payload package; the session
 * {@code interaction} policy block lives on
 * {@code websocket.message.payload.SessionOpenPayload} (plan Task 1).</p>
 */
class HostInteractionProtocolTest extends IntegrationTestBase {

    @Autowired HostControlWebSocketHandler handler;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired TestDataBuilder data;
    @Autowired SessionContextAssembler sessionAssembler;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ------------------------------------------------------------------
    // Fixtures mirroring the protocol section 22 JSON blocks verbatim.
    // ------------------------------------------------------------------

    private static final String EXECUTION_ID = "44444444-4444-4444-8444-444444444444";
    private static final String DISPATCH_ID = "66666666-6666-4666-8666-666666666666";
    private static final String INTERACTION_ID = "88888888-8888-4888-8888-888888888888";
    private static final String ACTOR_USER_ID = "99999999-9999-4999-8999-999999999999";
    private static final String CONTROL_REQUEST_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String T0 = "2026-10-03T10:00:00.000Z";
    private static final String T1 = "2026-10-03T10:01:00.000Z";
    private static final String T3 = "2026-10-03T10:03:00.000Z";
    private static final String T5 = "2026-10-03T10:05:00.000Z";

    /** section 22.4 verbatim engine command. */
    private static final String CONTROL_HOLD_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
              "action": "HOLD", "reasonCode": "USER_REQUESTED",
              "holdPolicy": { "idleResumeAfterSeconds": 300 }, "controlRequestId": null }
            """.formatted(EXECUTION_ID, DISPATCH_ID);

    /** section 22.4 verbatim host state report. */
    private static final String CONTROL_STATE_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
              "stateSequence": 2, "status": "HELD", "effectiveState": "HELD",
              "reasonCode": "USER_REQUESTED", "changedAt": "%s",
              "idleResumeAt": "%s", "safePoint": "BEFORE_MODEL_CALL",
              "rejectedControlRevision": null, "errorCode": null }
            """.formatted(EXECUTION_ID, DISPATCH_ID, T0, T5);

    /** section 22.6 verbatim engine request. */
    private static final String INTERACTION_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
              "ordinal": 1, "actorUserId": "%s",
              "message": { "text": "Why is verification taking longer?" },
              "acceptedAt": "%s", "responseDeadline": "%s" }
            """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, ACTOR_USER_ID, T1, T3);

    /** section 22.6 verbatim ephemeral delta. */
    private static final String INTERACTION_DELTA_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
              "index": 0, "text": "Verification is" }
            """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID);

    /** section 22.6 verbatim complete outcome. */
    private static final String INTERACTION_COMPLETE_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
              "ordinal": 1,
              "answer": { "text": "The verifier is checking the current candidate." },
              "usage": { "inputTokens": 120, "outputTokens": 30, "modelId": "gpt-4o" },
              "usageStatus": "KNOWN", "controlRequestIds": [], "completedAt": "%s" }
            """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, T1);

    /** section 22.6 verbatim failure outcome. */
    private static final String INTERACTION_FAILED_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
              "ordinal": 1,
              "error": { "errorCode": "INTERACTION_TIMEOUT", "message": "deadline",
                         "retryable": false },
              "usage": { "inputTokens": 40, "outputTokens": null, "modelId": "gpt-4o" },
              "usageStatus": "KNOWN", "controlRequestIds": [], "completedAt": "%s" }
            """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, T1);

    /** section 22.7 verbatim host proposal. */
    private static final String CONTROL_REQUEST_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
              "controlRequestId": "%s", "action": "CANCEL",
              "explanation": "User asked to stop this attempt." }
            """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID);

    /** section 22.7 verbatim engine disposition. */
    private static final String CONTROL_REQUEST_RESOLVED_JSON = """
            { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
              "controlRequestId": "%s", "resolutionRevision": 1,
              "status": "CONFIRMATION_REQUIRED", "expiresAt": "%s",
              "commandMessageId": null, "controlRevision": null, "errorCode": null }
            """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID, T3);

    // ------------------------------------------------------------------
    // Golden vectors: every section 22 payload parses verbatim.
    // ------------------------------------------------------------------

    @SuppressWarnings("unused")
    private static Stream<Arguments> goldenPayloads() {
        return Stream.of(
                Arguments.of("execution.control", CONTROL_HOLD_JSON),
                Arguments.of("execution.control.state", CONTROL_STATE_JSON),
                Arguments.of("execution.interaction", INTERACTION_JSON),
                Arguments.of("execution.interaction.delta", INTERACTION_DELTA_JSON),
                Arguments.of("execution.interaction.complete", INTERACTION_COMPLETE_JSON),
                Arguments.of("execution.interaction.failed", INTERACTION_FAILED_JSON),
                Arguments.of("execution.control.request", CONTROL_REQUEST_JSON),
                Arguments.of("execution.control.request.resolved", CONTROL_REQUEST_RESOLVED_JSON));
    }

    @ParameterizedTest(name = "{0} parses the section-22 golden payload verbatim")
    @MethodSource("goldenPayloads")
    void parsesGoldenPayload(String label, String json) throws Exception {
        Object parsed = switch (label) {
            case "execution.control" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload.class);
            case "execution.control.state" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionControlStatePayload.class);
            case "execution.interaction" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionInteractionPayload.class);
            case "execution.interaction.delta" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionInteractionDeltaPayload.class);
            case "execution.interaction.complete" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload.class);
            case "execution.interaction.failed" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload.class);
            case "execution.control.request" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload.class);
            case "execution.control.request.resolved" -> mapper.readValue(json, ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload.class);
            default -> throw new IllegalArgumentException(label);
        };
        assertThat(parsed).isNotNull();
    }

    @Test
    @DisplayName("execution.control: HOLD requires holdPolicy, CONTINUE forbids it (section 22.4)")
    void holdWithoutPolicyIsRejected() {
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "action": "HOLD", "reasonCode": "USER_REQUESTED" }
                """.formatted(EXECUTION_ID, DISPATCH_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasMessageContaining("holdPolicy");
    }

    @Test
    @DisplayName("execution.control: unknown action is rejected (section 22.4)")
    void unknownControlActionIsRejected() {
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "action": "CANCEL", "reasonCode": "USER_REQUESTED",
                  "holdPolicy": { "idleResumeAfterSeconds": 300 } }
                """.formatted(EXECUTION_ID, DISPATCH_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("execution.control: holdPolicy below the section 22.2 idle domain fails parse (30..3600)")
    void holdPolicyBelowIdleDomainIsRejected() throws Exception {
        // §22.2 tighten-only idle domain — the same 30..3600 range the SDK
        // zod schema (interactionPolicySchema / holdPolicy) enforces.
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "action": "HOLD", "reasonCode": "USER_REQUESTED",
                  "holdPolicy": { "idleResumeAfterSeconds": 1 } }
                """.formatted(EXECUTION_ID, DISPATCH_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasMessageContaining("idleResumeAfterSeconds");

        // Boundary mirror of the zod min(30)/max(3600): just outside fails.
        for (int outside : new int[] {29, 3601}) {
            int idle = outside;
            assertThatThrownBy(() -> mapper.readValue(
                    """
                    { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                      "action": "HOLD", "reasonCode": "USER_REQUESTED",
                      "holdPolicy": { "idleResumeAfterSeconds": %d } }
                    """.formatted(EXECUTION_ID, DISPATCH_ID, idle),
                    ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload.class))
                    .as("idleResumeAfterSeconds=%d must fail parse".formatted(idle))
                    .isInstanceOf(ValueInstantiationException.class)
                    .hasMessageContaining("idleResumeAfterSeconds");
        }
        // Inside the domain parses (HOLD golden vector already covers 300,
        // the §22.2 default; boundaries 30/3600 parse too).
        assertThat(mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "action": "HOLD", "reasonCode": "USER_REQUESTED",
                  "holdPolicy": { "idleResumeAfterSeconds": 30 } }
                """.formatted(EXECUTION_ID, DISPATCH_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload.class)
                .holdPolicy().idleResumeAfterSeconds()).isEqualTo(30);
        assertThat(mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "action": "HOLD", "reasonCode": "USER_REQUESTED",
                  "holdPolicy": { "idleResumeAfterSeconds": 3600 } }
                """.formatted(EXECUTION_ID, DISPATCH_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload.class)
                .holdPolicy().idleResumeAfterSeconds()).isEqualTo(3600);
    }

    @Test
    @DisplayName("execution.control.request.resolved: errorCode is the closed section 22.7 refusal catalogue")
    void resolvedErrorCodesAreClosed() throws Exception {
        for (String code : List.of("FORBIDDEN", "EXECUTION_TERMINAL",
                "INVALID_INTERACTION", "CONFIRMATION_EXPIRED")) {
            String json = """
                    { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                      "controlRequestId": "%s", "resolutionRevision": 1,
                      "status": "REJECTED", "expiresAt": null, "commandMessageId": null,
                      "controlRevision": null, "errorCode": "%s" }
                    """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID,
                    CONTROL_REQUEST_ID, code);
            assertThat(mapper.readValue(json,
                    ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload.class)
                    .errorCode().name()).isEqualTo(code);
        }
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "controlRequestId": "%s", "resolutionRevision": 1,
                  "status": "REJECTED", "expiresAt": null, "commandMessageId": null,
                  "controlRevision": null, "errorCode": "NOT_A_CODE" }
                """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("execution.control.state: REJECTED requires rejectedControlRevision (section 22.4)")
    void rejectedStateRequiresRevision() {
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 4,
                  "stateSequence": 9, "status": "REJECTED", "effectiveState": "HELD",
                  "reasonCode": "STALE_CONTROL_REVISION", "changedAt": "%s",
                  "idleResumeAt": null, "safePoint": null, "errorCode": null }
                """.formatted(EXECUTION_ID, DISPATCH_ID, T0),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlStatePayload.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasMessageContaining("rejectedControlRevision");
    }

    @Test
    @DisplayName("execution.control.state: status enum is closed (section 22.4)")
    void unknownStateStatusIsRejected() {
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "stateSequence": 2, "status": "PAUSED", "effectiveState": "HELD",
                  "reasonCode": "USER_REQUESTED", "changedAt": "%s",
                  "idleResumeAt": null, "safePoint": null, "errorCode": null }
                """.formatted(EXECUTION_ID, DISPATCH_ID, T0),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlStatePayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("execution.control.state: safePoint enum is closed (section 22.4)")
    void unknownSafePointIsRejected() {
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "controlRevision": 1,
                  "stateSequence": 2, "status": "HELD", "effectiveState": "HELD",
                  "reasonCode": "USER_REQUESTED", "changedAt": "%s",
                  "idleResumeAt": "%s", "safePoint": "MID_CALL", "errorCode": null }
                """.formatted(EXECUTION_ID, DISPATCH_ID, T0, T5),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlStatePayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("execution.interaction.failed: error codes are the section 22.6 catalogue")
    void failedErrorCodesAreClosed() throws Exception {
        for (String code : List.of("INTERACTION_TIMEOUT", "MODEL_ERROR",
                "OUTPUT_LIMIT_EXCEEDED", "TOKEN_USAGE_UNAVAILABLE",
                "EXECUTION_TERMINAL", "EXECUTION_CANCELLED",
                "HOST_STATE_LOST", "CAPTURE_BLOCKED")) {
            String json = """
                    { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                      "ordinal": 1,
                      "error": { "errorCode": "%s", "message": "x", "retryable": false },
                      "usage": null, "usageStatus": "UNKNOWN", "controlRequestIds": [],
                      "completedAt": "%s" }
                    """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, code, T1);
            assertThat(mapper.readValue(json,
                    ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload.class)
                    .error().errorCode().name()).isEqualTo(code);
        }
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "ordinal": 1,
                  "error": { "errorCode": "NOT_IN_CATALOGUE", "message": "x", "retryable": false },
                  "usage": null, "usageStatus": "UNKNOWN", "controlRequestIds": [],
                  "completedAt": "%s" }
                """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, T1),
                ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("execution.control.request: action is HOLD/CONTINUE/CANCEL (section 22.7)")
    void proposalActionEnumIsClosed() throws Exception {
        for (String action : List.of("HOLD", "CONTINUE", "CANCEL")) {
            String json = """
                    { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                      "controlRequestId": "%s", "action": "%s" }
                    """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID, action);
            assertThat(mapper.readValue(json,
                    ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload.class)
                    .action().name()).isEqualTo(action);
        }
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "controlRequestId": "%s", "action": "PAUSE" }
                """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("execution.control.request.resolved: status enum + ACCEPTED commandMessageId (section 22.7)")
    void resolutionStatusEnumIsClosed() throws Exception {
        // ACCEPTED requires commandMessageId; CONFIRMATION_REQUIRED requires
        // expiresAt; the others take both null (section 22.7 shapes).
        for (String status : List.of("ACCEPTED", "CONFIRMATION_REQUIRED",
                "REJECTED", "DECLINED", "EXPIRED")) {
            boolean accepted = "ACCEPTED".equals(status);
            boolean needsExpiry = "CONFIRMATION_REQUIRED".equals(status);
            String commandMessageId = accepted ? "\"m-cmd-1\"" : "null";
            String expiresAt = needsExpiry ? "\"" + T3 + "\"" : "null";
            String controlRevision = accepted ? "1" : "null";
            String json = """
                    { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                      "controlRequestId": "%s", "resolutionRevision": 1,
                      "status": "%s", "expiresAt": %s, "commandMessageId": %s,
                      "controlRevision": %s, "errorCode": null }
                    """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID,
                    CONTROL_REQUEST_ID, status, expiresAt, commandMessageId, controlRevision);
            assertThat(mapper.readValue(json,
                    ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload.class)
                    .status().name()).isEqualTo(status);
        }
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "controlRequestId": "%s", "resolutionRevision": 1,
                  "status": "PENDING", "expiresAt": null, "commandMessageId": null,
                  "controlRevision": null, "errorCode": null }
                """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    @DisplayName("execution.control.request.resolved: ACCEPTED requires commandMessageId (section 22.7)")
    void acceptedResolutionRequiresCommandMessageId() {
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "controlRequestId": "%s", "resolutionRevision": 2,
                  "status": "ACCEPTED", "expiresAt": null, "commandMessageId": null,
                  "controlRevision": 1, "errorCode": null }
                """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasMessageContaining("commandMessageId");
    }

    @Test
    @DisplayName("execution.control.request.resolved: CONFIRMATION_REQUIRED requires expiresAt (section 22.7)")
    void confirmationRequiresExpiry() {
        assertThatThrownBy(() -> mapper.readValue(
                """
                { "executionId": "%s", "dispatchId": "%s", "interactionId": "%s",
                  "controlRequestId": "%s", "resolutionRevision": 1,
                  "status": "CONFIRMATION_REQUIRED", "expiresAt": null,
                  "commandMessageId": null, "controlRevision": null, "errorCode": null }
                """.formatted(EXECUTION_ID, DISPATCH_ID, INTERACTION_ID, CONTROL_REQUEST_ID),
                ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload.class))
                .isInstanceOf(ValueInstantiationException.class)
                .hasMessageContaining("confirmation expiry");
    }

    @Test
    @DisplayName("execution.interaction: USAGE/usageStatus enums round-trip (section 22.6)")
    void usageStatusEnumRoundTrips() throws Exception {
        var complete = mapper.readValue(INTERACTION_COMPLETE_JSON,
                ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload.class);
        assertThat(complete.usageStatus()).isEqualTo(
                ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload.UsageStatus.KNOWN);
        assertThat(complete.usage().inputTokens()).isEqualTo(120);
        assertThat(complete.controlRequestIds()).isEmpty();

        var failed = mapper.readValue(
                INTERACTION_FAILED_JSON.replace("\"usageStatus\": \"KNOWN\"",
                        "\"usageStatus\": \"UNKNOWN\""),
                ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload.class);
        assertThat(failed.usageStatus()).isEqualTo(
                ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload.UsageStatus.UNKNOWN);
    }

    // ------------------------------------------------------------------
    // Current capability validation at host.open (section 22.2, fail-closed).
    // ------------------------------------------------------------------

    @SuppressWarnings("unused")
    private static Stream<Arguments> invalidHostCapabilities() {
        return Stream.of(
                Arguments.of("absent", (String) null),
                Arguments.of("unsupported version",
                        "{ \"sessionInteraction\": { \"version\": 2, \"temporaryHold\": true } }"),
                Arguments.of("temporaryHold false",
                        "{ \"sessionInteraction\": { \"version\": 1, \"temporaryHold\": false } }"),
                Arguments.of("wrong shape",
                        "{ \"sessionInteraction\": \"yes\" }"),
                Arguments.of("null block",
                        "{ \"sessionInteraction\": null }"));
    }

    @ParameterizedTest(name = "host.open rejects {0} sessionInteraction capability")
    @MethodSource("invalidHostCapabilities")
    @DisplayName("host.open fails closed without the current sessionInteraction capability")
    void missingOrUnsupportedCapabilityRejectsHostOpen(String variant, String capabilitiesJson)
            throws Exception {
        AgentHostCreationResult created = data.agent().named("cap-host").withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = stubSession(host);
        String capabilities = capabilitiesJson == null ? "{}" : capabilitiesJson;
        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": %s, "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID(), capabilities);
        handler.handleMessage(session, new TextMessage(open));

        JsonNode reply = soleReply(session);
        assertThat(reply.path("type").asText()).isEqualTo("protocol.error");
        assertThat(reply.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.INVALID_MESSAGE);
        assertThat(reply.path("payload").path("message").asText())
                .contains("sessionInteraction");
        // No instance row was minted (fail closed, no old-host fallback).
        assertThat(instances.findByAgentHostIdAndStatus(
                host.getId(), AgentHostInstance.Status.OPEN)).isEmpty();
    }

    @Test
    @DisplayName("host.open accepts the section 22.2 capability and host.opened echoes acceptedCapabilities")
    void validCapabilityOpensAndEchoesAcceptedCapabilities() throws Exception {
        AgentHostCreationResult created = data.agent().named("cap-ok-host").withMaxAgents(10).create();
        AgentHost host = created.agent();

        WebSocketSession session = stubSession(host);
        String open = """
                { "protocolVersion": 1, "messageId": "m-open", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": { "sessionInteraction": { "version": 1, "temporaryHold": true } },
                  "reportedCapacity": {} } }
                """.formatted(Instant.now(), UUID.randomUUID());
        handler.handleMessage(session, new TextMessage(open));

        JsonNode reply = soleReply(session);
        assertThat(reply.path("type").asText()).isEqualTo("host.opened");
        JsonNode accepted = reply.path("payload").path("acceptedCapabilities");
        assertThat(accepted.path("sessionInteraction").path("version").asInt()).isEqualTo(1);
        assertThat(accepted.path("sessionInteraction").path("temporaryHold").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("same-nonce replay requires the capability too (no grandfathered hosts)")
    void replayRequiresCapability() throws Exception {
        AgentHostCreationResult created = data.agent().named("cap-replay-host").withMaxAgents(10).create();
        AgentHost host = created.agent();
        UUID nonce = UUID.randomUUID();

        // First open WITH the capability mints an instance.
        WebSocketSession session = stubSession(host);
        handler.handleMessage(session, new TextMessage(openFrame(nonce, true)));
        assertThat(soleReply(session).path("type").asText()).isEqualTo("host.opened");

        // Replay WITHOUT the capability is rejected — stale fixture, fatal.
        WebSocketSession replaySession = stubSession(host);
        handler.handleMessage(replaySession, new TextMessage(openFrame(nonce, false)));
        JsonNode error = soleReply(replaySession);
        assertThat(error.path("type").asText()).isEqualTo("protocol.error");
        assertThat(error.path("payload").path("code").asText())
                .isEqualTo(HostProtocol.INVALID_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Orchestration session.open policy assembly + validation (section 22.2).
    // ------------------------------------------------------------------

    @Test
    @DisplayName("WORKFLOW session.open carries the interaction policy block (section 22.2)")
    void workflowSessionOpenCarriesInteractionPolicy() throws Exception {
        AgentProfileFixture profile = createProfile();
        SessionOpenPayload payload = sessionAssembler.assemble("WORKFLOW",
                UUID.randomUUID(), createProject().getId(), agentProfileVersionService
                        .requirePublished(profile.profileId()).getProfileId());

        SessionOpenPayload.InteractionPolicy interaction = payload.interaction();
        assertThat(interaction).as("WORKFLOW sessions require the interaction block").isNotNull();
        assertThat(interaction.version()).isEqualTo(1);
        assertThat(interaction.enabled()).isTrue();
        // The implementation defaults (section 22.2, InteractionProperties).
        assertThat(interaction.idleResumeAfterSeconds()).isEqualTo(300);
        assertThat(interaction.responseTimeoutSeconds()).isEqualTo(120);
        assertThat(interaction.maxInputBytes()).isEqualTo(16384);
        assertThat(interaction.maxOutputBytes()).isEqualTo(65536);
        assertThat(interaction.maxModelIterations()).isEqualTo(8);
        assertThat(interaction.maxHistoryBytes()).isEqualTo(262144);
        assertThat(interaction.transcriptRetentionDays()).isEqualTo(30);
        assertThat(interaction.contentMode())
                .isEqualTo(SessionOpenPayload.InteractionPolicy.ContentMode.USER_CHAT_ONLY);
    }

    @Test
    @DisplayName("CONVERSATION session.open carries NO interaction block (section 22.2)")
    void conversationSessionOpenCarriesNoInteractionPolicy() throws Exception {
        AgentProfileFixture profile = createProfile();
        SessionOpenPayload payload = sessionAssembler.assemble("CONVERSATION",
                UUID.randomUUID(), createProject().getId(), agentProfileVersionService
                        .requirePublished(profile.profileId()).getProfileId());

        assertThat(payload.interaction())
                .as("conversation sessions do not receive the interaction block")
                .isNull();
    }

    @Test
    @DisplayName("InteractionProperties: defaults + tighten-only bounds + fail-closed validation")
    void interactionPropertiesDefaultsAndBounds() {
        var defaults = ai.myrmec.engine.inference.execution.interaction.InteractionProperties.defaults();
        assertThat(defaults.version()).isEqualTo(1);
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.idleResumeAfterSeconds()).isEqualTo(300);
        assertThat(defaults.responseTimeoutSeconds()).isEqualTo(120);
        assertThat(defaults.maxInputBytes()).isEqualTo(16384);
        assertThat(defaults.maxOutputBytes()).isEqualTo(65536);
        assertThat(defaults.maxModelIterations()).isEqualTo(8);
        assertThat(defaults.maxHistoryBytes()).isEqualTo(262144);
        assertThat(defaults.transcriptRetentionDays()).isEqualTo(30);
        assertThat(defaults.contentMode()).isEqualTo(
                ai.myrmec.engine.inference.execution.interaction.InteractionProperties.ContentMode.USER_CHAT_ONLY);

        // Out-of-bounds values fail at construction (fail-closed, section 22.2).
        for (var violation : List.of(
                "idleResumeAfterSeconds=29",
                "idleResumeAfterSeconds=3601",
                "responseTimeoutSeconds=4",
                "responseTimeoutSeconds=301",
                "maxInputBytes=16385",
                "maxOutputBytes=65537",
                "maxModelIterations=9",
                "maxHistoryBytes=262145",
                "transcriptRetentionDays=31",
                "transcriptRetentionDays=0")) {
            String[] parts = violation.split("=");
            String field = parts[0];
            int value = Integer.parseInt(parts[1]);
            assertThatThrownBy(() -> tighten(field, value))
                    .as("out-of-bounds policy must fail: " + violation)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(field);
        }
        // The defaults themselves validate.
        ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                .requireWithinBounds(defaults);
    }

    @Test
    @DisplayName("InteractionProperties.Policy constructor validates eagerly (fail-closed)")
    void policyConstructorValidatesEagerly() {
        assertThatThrownBy(() -> new ai.myrmec.engine.inference.execution.interaction
                .InteractionProperties.Policy(
                        2, true, 300, 120, 16384, 65536, 8, 262144, 30,
                        ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                .ContentMode.USER_CHAT_ONLY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new ai.myrmec.engine.inference.execution.interaction
                .InteractionProperties.Policy(
                        1, true, 300, 120, 16384, 65536, 8, 262144, 30, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentMode");
    }

    /**
     * Build a defaults policy with ONE field replaced by the given value.
     * The Policy constructor validates eagerly, so constructing with an
     * out-of-bounds value throws — exactly the fail-closed behavior under
     * test in the bounds loop.
     */
    private void tighten(String field, int value) {
        switch (field) {
            case "idleResumeAfterSeconds" ->
                    new ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy(
                            1, true, value, 120, 16384, 65536, 8, 262144, 30,
                            ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                    .ContentMode.USER_CHAT_ONLY);
            case "responseTimeoutSeconds" ->
                    new ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy(
                            1, true, 300, value, 16384, 65536, 8, 262144, 30,
                            ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                    .ContentMode.USER_CHAT_ONLY);
            case "maxInputBytes" ->
                    new ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy(
                            1, true, 300, 120, value, 65536, 8, 262144, 30,
                            ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                    .ContentMode.USER_CHAT_ONLY);
            case "maxOutputBytes" ->
                    new ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy(
                            1, true, 300, 120, 16384, value, 8, 262144, 30,
                            ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                    .ContentMode.USER_CHAT_ONLY);
            case "maxModelIterations" ->
                    new ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy(
                            1, true, 300, 120, 16384, 65536, value, 262144, 30,
                            ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                    .ContentMode.USER_CHAT_ONLY);
            case "maxHistoryBytes" ->
                    new ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy(
                            1, true, 300, 120, 16384, 65536, 8, value, 30,
                            ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                    .ContentMode.USER_CHAT_ONLY);
            case "transcriptRetentionDays" ->
                    new ai.myrmec.engine.inference.execution.interaction.InteractionProperties.Policy(
                            1, true, 300, 120, 16384, 65536, 8, 262144, value,
                            ai.myrmec.engine.inference.execution.interaction.InteractionProperties
                                    .ContentMode.USER_CHAT_ONLY);
            default -> throw new IllegalArgumentException("unknown field: " + field);
        }
    }

    // ------------------------------------------------------------------
    // Harness
    // ------------------------------------------------------------------

    private record AgentProfileFixture(UUID profileId) {}

    @Autowired
    ai.myrmec.engine.agent.AgentProfileVersionService agentProfileVersionService;

    private AgentProfileFixture createProfile() {
        // createProfile publishes version 1 when behaviour content is present
        // (system prompt) — requirePublished then resolves that version row.
        var profile = data.agentProfile()
                .named("interaction-profile")
                .withSystemPrompt("interaction contract test prompt")
                .create();
        return new AgentProfileFixture(profile.getId());
    }

    private ai.myrmec.engine.project.Project createProject() {
        return data.project().named("interaction-project").create();
    }

    private WebSocketSession stubSession(AgentHost host) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        lenient().when(session.getId()).thenReturn("hi-sock-" + UUID.randomUUID());
        lenient().when(session.isOpen()).thenReturn(true);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_ID, host.getId());
        attrs.put(HostControlHandshakeInterceptor.ATTR_HOST_NAME, host.getName());
        lenient().when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    private String openFrame(UUID nonce, boolean withCapability) {
        String capabilities = withCapability
                ? "{ \"sessionInteraction\": { \"version\": 1, \"temporaryHold\": true } }"
                : "{}";
        return """
                { "protocolVersion": 1, "messageId": "m-%s", "type": "host.open",
                  "sentAt": "%s", "payload": { "instanceNonce": "%s", "hostname": "laptop",
                  "runtimeVersion": "1.8.0", "supportedProtocolVersions": [1], "poolSize": 2,
                  "capabilities": %s, "reportedCapacity": {} } }
                """.formatted(UUID.randomUUID(), Instant.now(), nonce, capabilities);
    }

    private JsonNode soleReply(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        return mapper.readTree(captor.getValue().getPayload());
    }
}