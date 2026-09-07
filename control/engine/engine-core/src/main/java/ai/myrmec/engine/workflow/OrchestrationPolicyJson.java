// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parse the Profile version's stored {@code gitPolicy} JSON
 * ({@code {"allowCheckpoint": bool, "allowPush": bool}}). Fail-safe
 * defaults: checkpoint allowed, push denied (§16.1: {@code pushToRemote}
 * defaults false; only the opt-in remote-Git scenario may set it true).
 */
public final class OrchestrationPolicyJson {

    public final boolean allowCheckpoint;
    public final boolean allowPush;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrchestrationPolicyJson(boolean allowCheckpoint, boolean allowPush) {
        this.allowCheckpoint = allowCheckpoint;
        this.allowPush = allowPush;
    }

    /** Fail-safe defaults: checkpoint allowed, push denied. */
    public static OrchestrationPolicyJson parse(String json) {
        if (json == null || json.isBlank()) {
            return new OrchestrationPolicyJson(true, false);
        }
        try {
            var node = MAPPER.readTree(json);
            return new OrchestrationPolicyJson(
                    node.path("allowCheckpoint").asBoolean(true),
                    node.path("allowPush").asBoolean(false));
        } catch (Exception e) {
            // Malformed stored JSON: fail safe — checkpoint allowed, push denied.
            return new OrchestrationPolicyJson(true, false);
        }
    }
}