// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host;

/**
 * Denylist redaction for host-control frames destined for logs (design §14
 * "Redaction": no PSK material may appear in captured engine logs).
 *
 * <p>The only secret-carrying host-control payload today is
 * {@code host.opened} ({@code psk} / {@code pskKeyId}). {@code pskKeyId} is
 * an audit identifier and stays visible; the base64 {@code psk} value is
 * replaced with {@link #REDACTED}. This is a defense-in-depth companion to
 * {@code HostOpenedPayload.toString()}: if a caller serializes a frame or
 * payload to a log line (raw JSON string, debug dump, crash trace), it MUST
 * route the text through {@link #redact(String)} first.</p>
 */
public final class HostFrameLogRedactor {

    /** Marker substituted for every denied field value. */
    public static final String REDACTED = "<redacted>";

    private HostFrameLogRedactor() {
    }

    /**
     * Redact denylisted host-control fields from text about to be logged.
     * Handles both record {@code toString()} output ({@code psk=<value>})
     * and serialized JSON ({@code "psk":"<value>"}).
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text.replaceAll("(\"psk\"\\s*:\\s*\")[^\"]*(\")", "$1" + REDACTED + "$2");
        out = out.replaceAll("(psk=)[^,\\]]*", "$1" + REDACTED);
        return out;
    }
}