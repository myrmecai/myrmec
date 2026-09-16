// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.setting.SystemSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Typed reads of the model-gateway system settings (credential-envelope
 * design &sect;5.1, platform scope, {@code PLATFORM_ADMIN} only).
 *
 * <p>Keys are provisioned by migrations (see changeset 026); reads are
 * forgiving and take caller-supplied defaults when the row is absent or
 * blank, per {@link SystemSettingService} convention.
 */
@Component
@RequiredArgsConstructor
public class ModelGatewaySettings {

    /** Whether the org model gateway is deployed and reachable. */
    public static final String ENABLED_KEY = "modelGateway.enabled";

    /** The gateway's OpenAI-compatible base endpoint (required when enabled). */
    public static final String ENDPOINT_KEY = "modelGateway.endpoint";

    private final SystemSettingService systemSettingService;

    /**
     * Whether Gateway mode is backed by a live gateway. Default {@code false}
     * until Phase 2 GA.
     */
    public boolean gatewayEnabled() {
        return systemSettingService.getBoolean(ENABLED_KEY, false);
    }

    /**
     * The gateway endpoint (empty string when unset).
     */
    public String gatewayEndpoint() {
        return systemSettingService.getString(ENDPOINT_KEY, "");
    }
}