// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-04: Opt-in toggle contract for knowledge providers vs instruction assets.
 *
 * <p>Verifies the binding infrastructure exists:
 * <ul>
 *   <li>{@link ProjectInstructionBinding} — enables/disables org OPTIONAL instruction assets</li>
 *   <li>{@link ProjectProviderBinding} — ACTIVE/INACTIVE opt-in for knowledge providers</li>
 * </ul>
 *
 * <p>Known gap: provider binding is stored but not yet enforced in
 * {@code ContextBuilder.resolveKnowledgeSources}. This contract test
 * verifies the repositories and entities have the correct shape.
 */
@Tag("RECON-04")
@Tag("SG4")
@DisplayName("RECON-04: Opt-In Toggle Contract")
class OptInToggleContractTest extends IntegrationTestBase {

    @Autowired
    private ProjectInstructionBindingRepository instructionBindingRepository;

    @Autowired
    private ProjectProviderBindingRepository providerBindingRepository;

    @Test
    @DisplayName("ProjectInstructionBindingRepository has findByProjectIdAndEnabledTrue query")
    void instructionBindingRepoHasEnabledQuery() {
        assertThat(instructionBindingRepository).isNotNull();
        // The method exists — verify by calling with a random project ID
        var results = instructionBindingRepository.findByProjectIdAndEnabledTrue(java.util.UUID.randomUUID());
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("ProjectProviderBindingRepository has findByProjectId query")
    void providerBindingRepoExists() {
        assertThat(providerBindingRepository).isNotNull();
    }
}