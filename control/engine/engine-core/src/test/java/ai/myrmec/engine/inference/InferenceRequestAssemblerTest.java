// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.context.ContextManifest;
import ai.myrmec.engine.context.ContextManifestRepository;
import ai.myrmec.engine.websocket.message.payload.InferenceAssignPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InferenceRequestAssembler} — verifies manifest
 * persistence (R1), sequenceNo propagation (R3), and stream flag (R7).
 */
@DisplayName("InferenceRequestAssembler")
@ExtendWith(MockitoExtension.class)
class InferenceRequestAssemblerTest {

    @Mock
    private ConversationTranscriptComposer conversationComposer;

    @Mock
    private WorkflowTranscriptComposer workflowComposer;

    @Mock
    private ContextManifestRepository contextManifestRepository;

    @InjectMocks
    private InferenceRequestAssembler assembler;

    @Test
    @DisplayName("conversation: stream=true, sequenceNo from spec")
    void testConversationAssembly() {
        UUID sessionId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        when(conversationComposer.compose(any())).thenReturn(List.of(
                new ai.myrmec.engine.websocket.message.payload.InferenceMessage(
                        "system", "Hello", null, null),
                new ai.myrmec.engine.websocket.message.payload.InferenceMessage(
                        "user", "Hi", null, null)
        ));
        when(contextManifestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .sessionId(sessionId)
                .requestId(requestId)
                .sequenceNo(5L)
                .conversationSystemPrompt("Hello")
                .userMessage("Hi")
                .build();

        InferenceAssignPayload payload = assembler.assemble(spec);

        assertThat(payload.stream()).isTrue();
        assertThat(payload.response().sequenceNo()).isEqualTo(5);
        assertThat(payload.messages()).hasSize(2);

        // Verify manifest was persisted (R1)
        ArgumentCaptor<ContextManifest> captor = ArgumentCaptor.forClass(ContextManifest.class);
        verify(contextManifestRepository).save(captor.capture());
        ContextManifest manifest = captor.getValue();
        assertThat(manifest.getServiceType()).isEqualTo("CONVERSATION");
        assertThat(manifest.getSessionId()).isEqualTo(sessionId);
        assertThat(manifest.getSequenceNo()).isEqualTo(5L);
        assertThat(manifest.getInstructionsIncluded()).isNotNull();
    }

    @Test
    @DisplayName("workflow: stream=false, sequenceNo from step index")
    void testWorkflowAssembly() {
        UUID sessionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        when(workflowComposer.compose(any())).thenReturn(List.of(
                new ai.myrmec.engine.websocket.message.payload.InferenceMessage(
                        "system", "Workflow system", null, null),
                new ai.myrmec.engine.websocket.message.payload.InferenceMessage(
                        "user", "Do work", null, null)
        ));
        when(contextManifestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("WORKFLOW")
                .sessionId(sessionId)
                .requestId(taskId)
                .sequenceNo(2L)
                .systemPrompt("Workflow system")
                .stepPrompt("Do work")
                .knowledge(List.of(
                        new InferenceRequestSpec.KnowledgeEntry(
                                "Standard A", "Content A", "STANDARD")
                ))
                .build();

        InferenceAssignPayload payload = assembler.assemble(spec);

        assertThat(payload.stream()).isFalse();
        assertThat(payload.response().sequenceNo()).isEqualTo(2);
        assertThat(payload.activeToolNames()).isEmpty();

        // Verify manifest persisted with knowledge entries
        ArgumentCaptor<ContextManifest> captor = ArgumentCaptor.forClass(ContextManifest.class);
        verify(contextManifestRepository).save(captor.capture());
        ContextManifest manifest = captor.getValue();
        assertThat(manifest.getServiceType()).isEqualTo("WORKFLOW");
        assertThat(manifest.getInstructionsIncluded()).hasSize(1);
        assertThat(manifest.getInstructionsIncluded().get(0).get("name")).isEqualTo("Standard A");
    }

    @Test
    @DisplayName("manifest persistence failure does not abort dispatch")
    void testManifestPersistenceFailureIsBestEffort() {
        when(conversationComposer.compose(any())).thenReturn(List.of(
                new ai.myrmec.engine.websocket.message.payload.InferenceMessage(
                        "system", "Hello", null, null)
        ));
        when(contextManifestRepository.save(any()))
                .thenThrow(new RuntimeException("DB connection lost"));

        InferenceRequestSpec spec = InferenceRequestSpec.builder()
                .serviceType("CONVERSATION")
                .sessionId(UUID.randomUUID())
                .requestId(UUID.randomUUID())
                .sequenceNo(1L)
                .conversationSystemPrompt("Hello")
                .userMessage("Hi")
                .build();

        // Should not throw — best-effort persistence
        InferenceAssignPayload payload = assembler.assemble(spec);
        assertThat(payload).isNotNull();
        assertThat(payload.messages()).hasSize(1);
    }
}