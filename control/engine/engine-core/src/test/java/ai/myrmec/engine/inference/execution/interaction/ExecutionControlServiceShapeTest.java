// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 6 shape-check (RED-phase guard): the named service/controller
 * classes and their exact plan-verbatim operation signatures exist.
 * Runs BEFORE the implementations are written — a compile failure of this
 * test IS the RED state.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExecutionControlServiceShapeTest {

    @Mock ai.myrmec.engine.inference.execution.SessionExecutionRepository executions;

    @Test
    @DisplayName("plan-verbatim service operations exist with the exact signatures")
    void serviceOperationsExist() throws Exception {
        Class<?> service = Class.forName(
                "ai.myrmec.engine.inference.execution.interaction.ExecutionControlService");

        assertThat(signatures(service, "control")).anyMatch(s ->
                s.contains(" ExecutionControlReceipt control(")
                || (s.contains(".control(") && s.contains("ControlRequest") && s.contains("ControlReceipt")));
        assertThat(signatures(service, "propose")).anyMatch(s ->
                s.contains(".propose(") && s.contains("ExecutionControlRequestPayload"));
        assertThat(signatures(service, "decide")).anyMatch(s ->
                s.contains(".decide(") && s.contains("Decision"));
        assertThat(signatures(service, "cancel")).anyMatch(s ->
                s.contains(".cancel(") && s.contains("ConfirmedCancelRequest"));
    }

    @Test
    @DisplayName("ExecutionScope is the complete section-4 ownership chain")
    void executionScopeIsTheOwnershipChain() throws Exception {
        Class<?> scope = Class.forName(
                "ai.myrmec.engine.inference.execution.interaction.ExecutionScope");
        List<String> fields = fieldsOf(scope);
        for (String member : List.of("projectId", "workflowId", "requestId",
                "taskId", "attemptId", "executionId")) {
            assertThat(fields).contains(member);
        }
    }

    @Test
    @DisplayName("supporting types exist")
    void supportingTypesExist() throws Exception {
        for (String name : List.of(
                "ai.myrmec.engine.inference.execution.interaction.ExecutionControlService",
                "ai.myrmec.engine.inference.execution.interaction.ExecutionInteractionController",
                "ai.myrmec.engine.inference.execution.interaction.ExecutionCommandOutboxDispatcher",
                "ai.myrmec.engine.inference.execution.interaction.ControlConfirmationSweeper",
                "ai.myrmec.engine.inference.execution.interaction.ExecutionScope",
                "ai.myrmec.engine.inference.execution.interaction.ControlRequest",
                "ai.myrmec.engine.inference.execution.interaction.ControlReceipt",
                "ai.myrmec.engine.inference.execution.interaction.ProposalReceipt",
                "ai.myrmec.engine.inference.execution.interaction.ConfirmedCancelRequest",
                "ai.myrmec.engine.inference.execution.interaction.Decision")) {
            Class.forName(name);
        }
    }

    // ------------------------------------------------------------------

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

    private static List<String> fieldsOf(Class<?> type) {
        List<String> found = new java.util.ArrayList<>();
        for (java.lang.reflect.Field f : type.getDeclaredFields()) {
            found.add(f.getName());
        }
        return found;
    }
}