// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-01: Additive stack contract for instruction assets.
 *
 * <p>Verifies that {@link ContextBuilder} exists as a Spring bean and
 * has the additive-stack assembly method. The additive stack composes
 * instruction assets from both ORG (priority +1000) and PROJECT (priority
 * +2000) scopes, sorted by effective priority.
 *
 * <p>This contract test verifies the infrastructure exists. Full
 * additive-stack behavior is tested via the golden journey tests (J1, J4)
 * and the existing {@link ContextBuilderTest}.
 */
@Tag("RECON-01")
@Tag("SG4")
@DisplayName("RECON-01: Additive Stack Contract")
class AdditiveStackContractTest extends IntegrationTestBase {

    @Autowired
    private ContextBuilder contextBuilder;

    @Test
    @DisplayName("ContextBuilder bean is available")
    void contextBuilderExists() {
        assertThat(contextBuilder).isNotNull();
    }

    @Test
    @DisplayName("ContextBuilder has assemble method (additive stack entry point)")
    void contextBuilderHasAssembleMethod() throws NoSuchMethodException {
        // Verify the class has at least one assemble method
        var methods = ContextBuilder.class.getDeclaredMethods();
        var hasAssemble = java.util.Arrays.stream(methods)
                .anyMatch(m -> m.getName().equals("assemble"));
        assertThat(hasAssemble)
                .as("ContextBuilder must have an 'assemble' method")
                .isTrue();
    }
}