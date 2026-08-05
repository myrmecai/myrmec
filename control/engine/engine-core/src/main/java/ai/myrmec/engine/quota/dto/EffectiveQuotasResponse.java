// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/** Wrapper for a list of effective quota read-models. */
@Value
@Builder
@Jacksonized
public class EffectiveQuotasResponse {
    List<EffectiveQuota> quotas;
}
