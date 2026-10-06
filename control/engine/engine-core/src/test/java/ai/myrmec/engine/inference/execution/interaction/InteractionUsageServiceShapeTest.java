// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 shape-check (RED-phase guard): the named service/sweeper classes
 * and their exact plan-verbatim operation signatures exist. Runs BEFORE
 * the implementations are written — a compile failure of this test IS the
 * RED state.
 */
class InteractionUsageServiceShapeTest {

    @Test
    @DisplayName("plan-verbatim service operations exist with the exact signatures")
    void serviceOperationsExist() throws Exception {
        Class<?> service = Class.forName(
                "ai.myrmec.engine.inference.execution.interaction.ExecutionInteractionService");

        assertThat(signatures(service, "admit")).anyMatch(s ->
                s.contains(".admit(") && s.contains("ExecutionScope")
                        && s.contains("UserPrincipal") && s.contains("InteractionAdmission"));
        assertThat(signatures(service, "complete")).anyMatch(s ->
                s.contains(".complete(") && s.contains("ExecutionScope"));
        assertThat(signatures(service, "fail")).anyMatch(s ->
                s.contains(".fail(") && s.contains("ExecutionScope"));

        Class<?> usage = Class.forName(
                "ai.myrmec.engine.inference.execution.interaction.InteractionUsageService");
        // The plan's four-argument signature grew idempotency/usage fields:
        // settleUsage(executionId, settlementId, source, interactionId,
        // usage, usageStatus) — toGenericString prints TYPES, so assert
        // the idempotency key's String type + the Source enum.
        assertThat(signatures(usage, "settleUsage")).anyMatch(s ->
                s.contains(".settleUsage(") && s.contains("java.lang.String")
                        && s.contains("InteractionUsageService$Source"));

        Class<?> sweeper = Class.forName(
                "ai.myrmec.engine.inference.execution.interaction.InteractionRetentionSweeper");
        assertThat(signatures(sweeper, "sweepOnce")).anyMatch(s -> s.contains(".sweepOnce("));
    }

    @Test
    @DisplayName("supporting types exist")
    void supportingTypesExist() {
        for (String name : List.of(
                "ai.myrmec.engine.inference.execution.interaction.ExecutionInteractionService",
                "ai.myrmec.engine.inference.execution.interaction.InteractionUsageService",
                "ai.myrmec.engine.inference.execution.interaction.InteractionRetentionSweeper",
                "ai.myrmec.engine.inference.execution.interaction.InteractionAdmission",
                "ai.myrmec.engine.inference.execution.interaction.InteractionProtocolException",
                "ai.myrmec.engine.inference.execution.interaction.InteractionCaptureBlockedException")) {
            assertThat(typeFor(name))
                    .as(name + " must exist")
                    .isNotNull();
        }
    }

    // ------------------------------------------------------------------

    private static Class<?> typeFor(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException expectedOnRed) {
            return null;
        }
    }

    private static List<String> signatures(Class<?> type, String method) {
        List<String> found = new java.util.ArrayList<>();
        try {
            for (Method m : type.getMethods()) {
                if (m.getName().equals(method)) {
                    found.add(m.toGenericString());
                }
            }
        } catch (Throwable ignored) {
            // absent class path reached at Class.forName
        }
        return found;
    }
}