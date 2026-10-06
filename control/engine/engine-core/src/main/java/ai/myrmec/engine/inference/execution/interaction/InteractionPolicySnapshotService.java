// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * §3.1/§4: persists the effective immutable interaction policy snapshot on
 * the orchestration execution at initialization. The assembler's
 * {@code session.open} carries the policy on the wire — this service is
 * the durable side: the execution row's {@code interaction_policy} column
 * is the snapshot the user API's ExecutionView projects.
 *
 * <p>Fail-closed: a missing policy for an orchestration execution is a
 * protocol violation (§22.2 — the block is REQUIRED for WORKFLOW). Session
 * orchestration executions are persisted through
 * {@code SessionContextAssembler} configuration — the assembler computes
 * the policy; this stamps it onto the row in the same transaction the
 * execution row is created in.</p>
 */
@Service
@RequiredArgsConstructor
public class InteractionPolicySnapshotService {

    private final SessionExecutionRepository executionRepository;

    /**
     * §3.1/§22.2: persist the effective policy for an orchestration
     * execution. Called when the execution row is created for a WORKFLOW
     * session (the natural single point is execution creation, where the
     * row is minted with its session's kind).
     *
     * @param executionId the orchestration execution
     * @param policy      the effective policy (from the session.open assembly)
     * @return true when the snapshot was persisted
     * @throws IllegalStateException when {@code policy} is null — missing
     *         orchestration policy fails closed (§22.2)
     */
    @Transactional
    public boolean persistOrchestrationPolicy(UUID executionId, InteractionProperties.Policy policy) {
        if (policy == null) {
            throw new IllegalStateException(
                    "orchestration session.open requires an interaction policy (§22.2): "
                            + "execution " + executionId + " has no policy to persist");
        }
        SessionExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> new IllegalStateException(
                        "execution " + executionId + " not found for the interaction policy snapshot"));
        execution.setInteractionPolicy(policyMap(policy));
        executionRepository.save(execution);
        return true;
    }

    /**
     * The §3.1 snapshot shape: the serialized InteractionProperties.Policy
     * as a JSON-friendly map (the entity column is a JSONB map). Public —
     * the execution-creation path (ExecutionRegistry) stamps the same
     * canonical serialization the persistence service writes.
     */
    public static Map<String, Object> policyMap(InteractionProperties.Policy policy) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", policy.version());
        snapshot.put("enabled", policy.enabled());
        snapshot.put("idleResumeAfterSeconds", policy.idleResumeAfterSeconds());
        snapshot.put("responseTimeoutSeconds", policy.responseTimeoutSeconds());
        snapshot.put("maxInputBytes", policy.maxInputBytes());
        snapshot.put("maxOutputBytes", policy.maxOutputBytes());
        snapshot.put("maxModelIterations", policy.maxModelIterations());
        snapshot.put("maxHistoryBytes", policy.maxHistoryBytes());
        snapshot.put("transcriptRetentionDays", policy.transcriptRetentionDays());
        snapshot.put("contentMode", policy.contentMode().name());
        return snapshot;
    }
}