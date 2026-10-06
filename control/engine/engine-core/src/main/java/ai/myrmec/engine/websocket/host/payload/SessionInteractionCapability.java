// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.websocket.host.payload;

/**
 * section 22.2 negotiated session-interaction capability: the typed shape of
 * {@code host.open.capabilities.sessionInteraction} (advertised by the SDK)
 * and {@code host.opened.acceptedCapabilities.sessionInteraction} (confirmed
 * by the engine).
 *
 * <p>This is current-contract validation, not an old-host fallback: version 1
 * with {@code temporaryHold=true} is REQUIRED — missing or unsupported
 * capability rejects host initialization explicitly (§17 cutover boundary).</p>
 */
public record SessionInteractionCapability(int version, boolean temporaryHold) {

    /** The V1 wire version (section 22.2). */
    public static final int VERSION = 1;

    /** The exact current-contract capability every SDK host advertises. */
    public static SessionInteractionCapability required() {
        return new SessionInteractionCapability(VERSION, true);
    }

    public SessionInteractionCapability {
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "sessionInteraction capability version must be " + VERSION
                            + " (got " + version + ") — current-contract validation has "
                            + "no old-peer fallback");
        }
        if (!temporaryHold) {
            throw new IllegalArgumentException(
                    "sessionInteraction.temporaryHold must be true (section 22.2)");
        }
    }
}