// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

/**
 * Standardized session-close reasons (protocol §9/§3.2 vocabulary) recorded
 * on the sessions row and echoed in session.close/close frames.
 */
public final class SessionCloseReason {
    private SessionCloseReason() {}
    public static final String CONVERSATION_ARCHIVED = "CONVERSATION_ARCHIVED";
    public static final String IDLE_LEASE_EXPIRED = "IDLE_LEASE_EXPIRED";
    public static final String OFFER_EXPIRED = "OFFER_EXPIRED";
    public static final String INITIALIZATION_FAILED = "INITIALIZATION_FAILED";
    public static final String HOST_LOST = "HOST_LOST";
    public static final String SUPERSEDED = "SUPERSEDED";
}
