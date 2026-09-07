// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.AgentProfileVersion;
import ai.myrmec.engine.agent.AgentProfileVersionRepository;
import ai.myrmec.engine.agent.AgentProfileVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * OrchestrationRunService (design §16.1/§16.7, plan Feature 10): pins the
 * run's Agent Profile version at request creation.
 *
 * <p>Each workflow request whose workflow contains an {@code ORCHESTRATOR}
 * step gets exactly one {@code orchestration_runs} row: the request UUID
 * IS the run id. The row pins the bound Profile's <b>currently published
 * version</b> plus a SHA-256 content digest over the version's behaviour
 * fields, so later Profile publishes never affect an in-flight run.</p>
 *
 * <p>Pure-inference workflows never create a run row — the ordinary
 * inference path is untouched.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrchestrationRunService {

    private final OrchestrationRunRepository runRepository;
    private final AgentProfileVersionService agentProfileVersionService;
    private final AgentProfileVersionRepository agentProfileVersionRepository;

    /**
     * Whether the workflow contains at least one ORCHESTRATOR step.
     */
    public static boolean hasOrchestratorStep(java.util.List<java.util.Map<String, Object>> steps) {
        if (steps == null) return false;
        return steps.stream().anyMatch(s -> "ORCHESTRATOR".equals(s.get("taskType")));
    }

    /**
     * Pin the run at request creation: resolve the bound Profile's currently
     * published version and persist the {@code orchestration_runs} row
     * (id == request id) with the version pin + content digest.
     *
     * <p>The profile id is the workflow's orchestration binding target for
     * the single workflow-local {@code agentProfileCode} alias (V1 requires
     * exactly one).</p>
     *
     * @return the pinned published version, or null when the workflow carries
     *         no orchestration binding (pure-inference)
     */
    @Transactional
    public AgentProfileVersion pinRun(
            UUID requestId,
            UUID workflowId,
            UUID projectId,
            UUID agentProfileId) {
        AgentProfileVersion published = agentProfileVersionService
                .requirePublished(agentProfileId);

        OrchestrationRun run = OrchestrationRun.builder()
                .id(requestId)
                .workflowId(workflowId)
                .projectId(projectId)
                .profileVersionId(published.getId())
                .profileVersionDigest(contentDigestOf(published))
                .build();
        runRepository.save(run);
        log.info("Run {} pinned to profile version {} (digest {}) for workflow {}",
                requestId, published.getId(), run.getProfileVersionDigest(), workflowId);
        return published;
    }

    /** The run's pinned published version, when the request is orchestrated. */
    @Transactional(readOnly = true)
    public AgentProfileVersion pinnedVersionOf(UUID runId) {
        OrchestrationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));
        // The FK pin is authoritative; the version row is resolved fresh.
        // (Pinned versions are immutable rows, so this lookup is stable.)
        return agentProfileVersionRepository.findById(run.getProfileVersionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Pinned profile version missing: " + run.getProfileVersionId()));
    }

    /**
     * SHA-256 over the version's canonical behaviour fields — the drift
     * audit digest (§16.7). Two runs of one workflow may pin different
     * digests after a re-publish; each run is fully pinned and auditable.
     */
    public static String contentDigestOf(AgentProfileVersion version) {
        StringBuilder canonical = new StringBuilder();
        canonical.append("systemPrompt=").append(nullSafe(version.getSystemPrompt())).append('\n');
        canonical.append("capabilities=").append(version.getCapabilities()).append('\n');
        canonical.append("tools=").append(version.getTools() == null
                ? java.util.List.of() : version.getTools().stream()
                        .map(t -> t.getCode())
                        .sorted()
                        .toList()).append('\n');
        canonical.append("defaultModel=").append(nullSafe(version.getDefaultModel())).append('\n');
        canonical.append("interactionMode=").append(version.getInteractionMode()).append('\n');
        canonical.append("commandTemplates=").append(nullSafe(version.getCommandTemplates())).append('\n');
        canonical.append("approvalPolicy=").append(nullSafe(version.getApprovalPolicy())).append('\n');
        canonical.append("gitPolicy=").append(nullSafe(version.getGitPolicy())).append('\n');
        canonical.append("requiredIsolation=").append(version.getRequiredIsolation()).append('\n');
        canonical.append("workspaceRetentionSeconds=").append(version.getWorkspaceRetentionSeconds())
                .append('\n');
        return OrchestrationIds.sha256Hex(
                canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Record the Host-supplied workspace identity on the run (§17.1). */
    @Transactional
    public void recordWorkspaceIdentity(UUID runId, String workspaceId, int generation,
                                        String leaseState, Instant leaseDeadline) {
        OrchestrationRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown run: " + runId));
        run.setWorkspaceId(workspaceId);
        run.setWorkspaceGeneration(generation);
        run.setLeaseState(leaseState);
        run.setLeaseDeadline(leaseDeadline);
        runRepository.save(run);
    }
}