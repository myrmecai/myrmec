// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import ai.myrmec.engine.context.ContextManifest;
import ai.myrmec.engine.context.ContextManifestRepository;
import ai.myrmec.engine.websocket.message.payload.InferenceAssignPayload;
import ai.myrmec.engine.websocket.message.payload.InferenceMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Inference request assembler (§6.2).
 *
 * <p>Builds an {@link InferenceAssignPayload} for a single turn/step by
 * delegating to the appropriate {@link TranscriptComposer} based on
 * service type, then wrapping the result with tool/generation/stream config.
 * Also persists a {@link ContextManifest} audit record (R1).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceRequestAssembler {

    private final ConversationTranscriptComposer conversationComposer;
    private final WorkflowTranscriptComposer workflowComposer;
    private final ContextManifestRepository contextManifestRepository;

    /**
     * Assemble an inference.assign payload and persist a ContextManifest.
     *
     * @param spec the request spec carrying service-specific inputs
     * @return the assembled InferenceAssignPayload (ready to send over WebSocket)
     */
    @Transactional
    public InferenceAssignPayload assemble(InferenceRequestSpec spec) {
        // 1. Pick the composer by service type
        TranscriptComposer composer = "WORKFLOW".equals(spec.getServiceType())
                ? workflowComposer
                : conversationComposer;

        // 2. Compose the transcript
        List<InferenceMessage> messages = composer.compose(spec);
        log.debug("Composed {} messages for {} session {}",
                messages.size(), spec.getServiceType(), spec.getSessionId());

        // 3. Active tool names — from spec for conversations, all for workflows
        List<String> activeToolNames = "WORKFLOW".equals(spec.getServiceType())
                ? List.of()  // Wired by dispatcher from step config; empty = all
                : spec.getActiveToolNames() != null ? spec.getActiveToolNames() : List.of();

        // 4. Generation config — no overrides by default
        InferenceAssignPayload.GenerationConfig generation = null;

        // 5. Stream flag — false for workflow, true for conversation (D3).
        //    When tools are active, disable streaming so the model returns
        //    tool calls reliably (streaming tool calls are not yet supported
        //    by all model adapters).
        boolean stream = "CONVERSATION".equals(spec.getServiceType())
                && (activeToolNames == null || activeToolNames.isEmpty());

        // 6. Response routing — sequence no from spec (R3: no longer hardcoded 0)
        int sequenceNo = (int) spec.getSequenceNo();
        String stepId = spec.getStepId();  // null for conversation turns

        // 7. Persist ContextManifest (R1 — audit trail for both paths)
        persistManifest(spec, messages);

        return new InferenceAssignPayload(
                spec.getRequestId(),
                spec.getSessionId(),
                spec.getServiceType(),
                messages,
                activeToolNames,
                generation,
                300,  // timeoutSeconds — default
                stream,
                new InferenceAssignPayload.ResponseRouting(sequenceNo, stepId),
                spec.getOrchestrationAssignment(),
                spec.getOrchestrationAssignmentDigest());
    }

    /**
     * Build and persist a {@link ContextManifest} from the assembled spec.
     * Best-effort: logs a warning on failure but never aborts the dispatch.
     */
    private void persistManifest(InferenceRequestSpec spec, List<InferenceMessage> messages) {
        try {
            ContextManifest manifest = new ContextManifest();
            manifest.setSessionId(spec.getSessionId());
            manifest.setServiceType(spec.getServiceType());
            manifest.setMessageId(null);  // conversation messageId not available here
            manifest.setSequenceNo(spec.getSequenceNo());
            manifest.setGovernanceProfileCode(spec.getGovernanceProfileCode());
            String pinning = spec.getContextPinning() != null ? spec.getContextPinning() : "PINNED_AT_START";
            manifest.setContextPinning(pinning);
            manifest.setTotalTokens(estimateTokens(messages));
            manifest.setBudgetTokens(0);  // no budget enforcement yet
            manifest.setTruncated(false);

            // Instruction/knowledge entries from the spec
            List<Map<String, Object>> instructionsIncluded = new ArrayList<>();
            if (spec.getKnowledge() != null) {
                for (InferenceRequestSpec.KnowledgeEntry entry : spec.getKnowledge()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", entry.name());
                    m.put("category", entry.category());
                    instructionsIncluded.add(m);
                }
            }
            manifest.setInstructionsIncluded(instructionsIncluded);
            manifest.setInstructionsExcluded(List.of());
            manifest.setInstructionsTruncated(List.of());
            manifest.setKnowledgeRetrieved(List.of());
            manifest.setKnowledgeIncluded(List.of());
            manifest.setKnowledgeTruncated(List.of());

            contextManifestRepository.save(manifest);
            log.debug("Persisted ContextManifest for {} session {} seq {}",
                    spec.getServiceType(), spec.getSessionId(), spec.getSequenceNo());
        } catch (Exception e) {
            log.warn("Failed to persist ContextManifest for {} session {}: {}",
                    spec.getServiceType(), spec.getSessionId(), e.getMessage());
        }
    }

    /**
     * Rough token estimate: ~4 chars per token per message.
     */
    private int estimateTokens(List<InferenceMessage> messages) {
        int chars = 0;
        for (InferenceMessage msg : messages) {
            if (msg.content() != null) {
                chars += msg.content().length();
            }
        }
        return chars / 4;
    }
}