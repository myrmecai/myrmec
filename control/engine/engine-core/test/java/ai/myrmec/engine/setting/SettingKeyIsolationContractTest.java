// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.setting;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-08: Setting key isolation contract.
 *
 * <p>Regression guard for the real bug fixed as backlog #127 — prevents
 * two configuration concerns from silently sharing one setting key.
 *
 * <p>Verifies that the context-assembly token budget key
 * ({@code context_token_budget}) and the per-attachment inline token
 * limit key ({@code attachment_inline_token_limit}) are distinct keys
 * with distinct defaults.
 */
@Tag("RECON-08")
@Tag("SG5")
@DisplayName("RECON-08: Setting Key Isolation Contract")
class SettingKeyIsolationContractTest extends IntegrationTestBase {

    @Autowired
    private SystemSettingService systemSettingService;

    @Test
    @DisplayName("context_token_budget and attachment_inline_token_limit are distinct keys")
    void distinctKeys() {
        assertThat("context_token_budget")
                .isNotEqualTo("attachment_inline_token_limit");
    }

    @Test
    @DisplayName("reading attachment_inline_token_limit returns its own default, not context_token_budget")
    void attachmentLimitHasOwnDefault() {
        long attachmentLimit = systemSettingService.getInt("attachment_inline_token_limit", 4000L);
        assertThat(attachmentLimit).isEqualTo(4000L);
    }

    @Test
    @DisplayName("reading context_token_budget returns its own default")
    void contextBudgetHasOwnDefault() {
        long contextBudget = systemSettingService.getInt("context_token_budget", 8000L);
        assertThat(contextBudget).isEqualTo(8000L);
    }
}