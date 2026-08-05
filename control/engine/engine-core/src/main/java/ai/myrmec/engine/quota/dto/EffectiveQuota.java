// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/** Read-model for a single budget row with its effective limit and consumption. */
@Value
@Builder
public class EffectiveQuota {
    UUID id;
    String scopeType;
    UUID scopeId;
    String name;
    String resourceType;
    String period;
    Long ownLimit;
    Long inheritedLimit;
    Long effectiveLimit;
    String quotaType;
    String enforcementMode;
    Long consumed;
    Long remaining;
    boolean atRisk;
    boolean exceeded;
    boolean paused;
}
