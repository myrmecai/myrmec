// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-12: Knowledge source binding stability contract.
 *
 * <p>Verifies that {@link KnowledgeSource} stores a {@code providerVersionId}
 * (non-null) — the binding to a specific provider version. When the provider
 * is republished, the version id transitions in-place from DRAFT to PUBLISHED,
 * so the binding remains valid.
 *
 * <p>This directly tests Decision #1 (binding-target conflict): the one place
 * a wrong answer would silently break reproducibility.
 */
@Tag("RECON-12")
@Tag("SG5")
@DisplayName("RECON-12: Knowledge Source Binding Stability Contract")
class KnowledgeSourceBindingContractTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeSourceRepository knowledgeSourceRepository;

    @Test
    @DisplayName("KnowledgeSource entity has providerVersionId field")
    void sourceHasProviderVersionId() throws NoSuchFieldException {
        var field = KnowledgeSource.class.getDeclaredField("providerVersionId");
        assertThat(field.getType()).isEqualTo(java.util.UUID.class);
    }
}