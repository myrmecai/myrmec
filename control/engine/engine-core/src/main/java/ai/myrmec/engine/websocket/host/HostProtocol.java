// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

/**
 * Unified agent-protocol constants (§4.3/§5/§14): message discriminators,
 * the negotiated wire version, and the protocol error codes.
 */
public final class HostProtocol {

    private HostProtocol() {}

    /** The wire contract version this engine speaks (§4.3). */
    public static final int SUPPORTED_VERSION = 1;

    // ---- Message types (§5 catalogue, host-lifecycle subset) ----
    public static final String HOST_OPEN = "host.open";
    public static final String HOST_OPENED = "host.opened";
    public static final String HOST_HEARTBEAT = "host.heartbeat";
    public static final String HOST_CAPACITY = "host.capacity";
    public static final String PROTOCOL_ERROR = "protocol.error";
    public static final String PROTOCOL_ACK = "protocol.ack";

    // ---- Error codes (§14) ----
    public static final String INVALID_MESSAGE = "INVALID_MESSAGE";
    public static final String UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    public static final String UNSUPPORTED_MESSAGE = "UNSUPPORTED_MESSAGE";
    public static final String INVALID_STATE = "INVALID_STATE";
    public static final String IDENTITY_MISMATCH = "IDENTITY_MISMATCH";

    // ---- Session allocation (§5 catalogue, §7 payloads) ----
    public static final String SESSION_OFFER = "session.offer";
    public static final String SESSION_ACCEPT = "session.accept";
    public static final String SESSION_REJECT = "session.reject";
    public static final String SESSION_OPEN = "session.open";
    public static final String SESSION_OPENED = "session.opened";
    public static final String SESSION_CLOSE = "session.close";
    public static final String SESSION_CLOSED = "session.closed";
}
