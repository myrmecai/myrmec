// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.node;

import java.util.UUID;

/**
 * Body of a peer-to-peer host-frame relay POST (protocol §20, decision H5;
 * §22.3/D7 for the dedicated form): {@code /api/v1/internal/agent-relay}
 * carries the target host instance the receiving replica must push to, plus
 * the frame as an opaque pre-serialized JSON string — the relay never binds
 * the payload to typed payloads.
 *
 * @param targetInstanceId the agent_host_instances row whose socket the
 *                         receiving replica owns
 * @param frame            the serialized §4.3 envelope, verbatim
 * @param dedicatedSessionId the session whose DEDICATED channel the frame must
 *                           ride (§22.3/D7): the receiving replica resolves
 *                           its channel registry ONLY — a null/absent value
 *                           keeps the pre-D7 form (control socket) for
 *                           lifecycle/admission frames
 */
public record AgentRelayRequest(UUID targetInstanceId, String frame, UUID dedicatedSessionId) {

    /** Pre-D7 form (control-socket delivery); Jackson also accepts the 3-arg shape. */
    public AgentRelayRequest(UUID targetInstanceId, String frame) {
        this(targetInstanceId, frame, null);
    }
}