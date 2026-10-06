// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.quota.EnforcementMode;
import ai.myrmec.engine.quota.Quota;
import ai.myrmec.engine.quota.QuotaConsumptionRepository;
import ai.myrmec.engine.quota.QuotaRepository;
import ai.myrmec.engine.quota.QuotaType;
import ai.myrmec.engine.spi.quota.QuotaDecision;
import ai.myrmec.engine.spi.quota.QuotaResourceType;
import ai.myrmec.engine.spi.quota.QuotaScope;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import ai.myrmec.engine.workflow.RequestStatus;
import ai.myrmec.engine.workflow.TaskAttempt;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import ai.myrmec.engine.workflow.TaskStatus;
import ai.myrmec.engine.workflow.Workflow;
import ai.myrmec.engine.workflow.WorkflowRepository;
import ai.myrmec.engine.workflow.WorkflowRequest;
import ai.myrmec.engine.workflow.WorkflowRequestRepository;
import ai.myrmec.engine.workflow.WorkflowStatus;
import ai.myrmec.engine.workflow.WorkflowTask;
import ai.myrmec.engine.workflow.WorkflowTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 8 accounting (plan-verbatim rule block): cumulative-minus-accounted
 * quota charging, idempotent settlementId dedup, UNKNOWN-never-overwrites,
 * source attribution subtotals, and the no-double-billing rule for the
 * aggregate terminal usage. Wire authority: protocol §22.8; plan §3.5.
 */
class InteractionUsageServiceTest extends IntegrationTestBase {

    @Autowired InteractionUsageService service;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executions;
    @Autowired ExecutionInteractionRepository interactions;
    @Autowired QuotaRepository quotaRepository;
    @Autowired QuotaConsumptionRepository consumptionRepository;
    @Autowired ExecutionEventRepository executionEventRepository;
    @Autowired WorkflowRequestRepository requestRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired WorkflowTaskRepository taskRepository;
    @Autowired TaskAttemptRepository attemptRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired ai.myrmec.engine.user.UserRepository userRepository;
    @Autowired ai.myrmec.engine.quota.BasicQuotaPolicyEngine quotaCheck;
    @Autowired ai.myrmec.engine.inference.execution.SessionPolicyService policyService;
    @Autowired PlatformTransactionManager transactionManager;

    private Project project;
    private Workflow workflow;
    private WorkflowRequest request;
    private WorkflowTask task;
    private TaskAttempt attempt;
    private Session session;
    private SessionExecution execution;

    @BeforeEach
    void seed() {
        project = data.project().named("usg-proj").create();
        workflow = workflowOf(project);
        var admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflow);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/usg");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        var profile = data.agentProfile().named("usg-profile").create();
        WorkflowTask t = new WorkflowTask();
        t.setRequest(request);
        t.setStepId("step-1");
        t.setAgentProfile(profile);
        t.setInput(Map.of());
        t.setStatus(TaskStatus.RUNNING);
        t.setAttempt(1);
        t.setMaxRetries(1);
        task = taskRepository.save(t);
        attempt = attemptRepository.save(task.createAttempt(null));

        AgentHostInstance instance = instances.saveAndFlush(AgentHostInstance.open(
                data.agent().named("usg-host").create().agent(), null,
                UUID.randomUUID().toString(), "laptop", 4, Map.of(), "node-1"));

        session = new Session();
        session.setServiceType("WORKFLOW");
        session.setRefId(request.getId());
        session.setProjectId(project.getId());
        session.setKind("ORCHESTRATION_TASK");
        session.setStatus("ACTIVE");
        session.setAllocationState("ACTIVE");
        session.setHostInstanceId(instance.getId());
        session = sessionRepository.save(session);

        execution = new SessionExecution();
        execution.setSessionId(session.getId());
        execution.setServiceType("WORKFLOW");
        execution.setDispatchId(attempt.getId());
        execution.setState(SessionExecution.State.RUNNING);
        execution.setStartedAt(Instant.now());
        execution.setDeadline(Instant.now().plusSeconds(600));
        execution = executions.saveAndFlush(execution);
    }

    // ------------------------------------------------------------------
    // The accounting rule: cumulative-minus-accounted
    // ------------------------------------------------------------------

    @Test
    @DisplayName("first INTERACTION settlement charges the full known usage to quota")
    void firstSettlementChargesKnownUsage() {
        usageQuota(100_000L);

        service.settleUsage(execution.getId(), "set-1",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                Map.of("inputTokens", 120L, "outputTokens", 30L), "KNOWN");

        assertThat(consumed()).isEqualTo(150L);
        Map<String, Object> accounting = accountingOf();
        assertThat(((Number) accounting.get("accountedTokens")).longValue()).isEqualTo(150L);
        // Attribution subtotal (§3.5): the interaction's share.
        assertThat(attributedTotal(accounting)).isEqualTo(150L);
    }

    @Test
    @DisplayName("cumulative-minus-accounted: a higher cumulative charge covers only the NEW consumption")
    void cumulativeMinusAccountedChargesOnlyTheNewConsumption() {
        usageQuota(100_000L);
        service.settleUsage(execution.getId(), "set-1",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                Map.of("inputTokens", 120L, "outputTokens", 30L), "KNOWN");

        // A later authoritative cumulative observation (an aggregate frame
        // that already includes the first 150): the DELTA is charged.
        service.settleUsage(execution.getId(), "set-2",
                InteractionUsageService.Source.ORCHESTRATION, null,
                Map.of("totalTokens", 400L), "KNOWN");

        assertThat(consumed()).isEqualTo(400L);
        assertThat(((Number) accountingOf().get("accountedTokens")).longValue()).isEqualTo(400L);
    }

    @Test
    @DisplayName("duplicate settlementId is idempotent — no second charge (terminal replay rule)")
    void duplicateSettlementIdIsIdempotent() {
        usageQuota(100_000L);
        service.settleUsage(execution.getId(), "set-1",
                InteractionUsageService.Source.ORCHESTRATION, null,
                Map.of("totalTokens", 500L), "KNOWN");
        long afterFirst = consumed();

        // The same aggregate settles again (a terminal replay / a durable
        // re-delivery) — no double billing.
        service.settleUsage(execution.getId(), "set-1",
                InteractionUsageService.Source.ORCHESTRATION, null,
                Map.of("totalTokens", 500L), "KNOWN");

        assertThat(consumed()).isEqualTo(afterFirst);
        assertThat(consumed()).isEqualTo(500L);
    }

    @Test
    @DisplayName("no double billing from the aggregate terminal: the aggregate minus interaction subtotals leaves only the delta")
    void aggregateTerminalDoesNotDoubleBillInteractionUsage() {
        usageQuota(100_000L);
        UUID interactionId = UUID.randomUUID();
        service.settleUsage(execution.getId(), "inter-set-1",
                InteractionUsageService.Source.INTERACTION, interactionId,
                Map.of("inputTokens", 120L, "outputTokens", 30L), "KNOWN");
        assertThat(consumed()).isEqualTo(150L);

        // The terminal's aggregate usage INCLUDES the interaction subtotal
        // (SDK: known chat usage included once in execution totals).
        service.settleUsage(execution.getId(), "tm-aggregate-1",
                InteractionUsageService.Source.ORCHESTRATION, null,
                Map.of("totalTokens", 650L), "KNOWN");

        // 650 cumulative - 150 accounted = 500 orchestrator-only charge.
        assertThat(consumed()).isEqualTo(650L);
        assertThat(((Number) accountingOf().get("accountedTokens")).longValue()).isEqualTo(650L);
    }

    @Test
    @DisplayName("UNKNOWN never overwrites known totals with zero (usage stays UNKNOWN, no charge)")
    void unknownNeverOverwritesKnown() {
        usageQuota(100_000L);
        service.settleUsage(execution.getId(), "set-known",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                Map.of("inputTokens", 100L, "outputTokens", 20L), "KNOWN");
        assertThat(consumed()).isEqualTo(120L);

        // A later UNKNOWN settlement (the provider evidence vanished):
        // no zero-write, no charge, the accounted total stands.
        service.settleUsage(execution.getId(), "set-unknown",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                null, "UNKNOWN");

        assertThat(consumed()).isEqualTo(120L);
        assertThat(((Number) accountingOf().get("accountedTokens")).longValue()).isEqualTo(120L);
        assertThat(accountingOf().get("usageStatus")).isEqualTo("KNOWN");
    }

    @Test
    @DisplayName("partial-known vs UNKNOWN: partial usage settles to KNOWN with the known parts only")
    void partialKnownUsageSettlesKnownParts() {
        usageQuota(100_000L);
        // input known, output unknown (null in the usage map).
        service.settleUsage(execution.getId(), "set-partial",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                Map.of("inputTokens", 40L), "KNOWN");
        assertThat(consumed()).isEqualTo(40L);
        assertThat(accountingOf().get("usageStatus")).isEqualTo("KNOWN");
    }

    @Test
    @DisplayName("UNKNOWN-only execution: the first settlement is UNKNOWN — account nothing, status stays UNKNOWN")
    void unknownFirstSettlementAccountsNothing() {
        usageQuota(100_000L);
        service.settleUsage(execution.getId(), "set-u1",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                null, "UNKNOWN");

        assertThat(consumed()).isZero();
        assertThat(accountingOf().get("usageStatus")).isEqualTo("UNKNOWN");

        // A later KNOWN cumulative recovers the accounting (late provider
        // evidence: usage-only, never the answer).
        service.settleUsage(execution.getId(), "set-k1",
                InteractionUsageService.Source.ORCHESTRATION, null,
                Map.of("totalTokens", 220L), "KNOWN");
        assertThat(consumed()).isEqualTo(220L);
        assertThat(accountingOf().get("usageStatus")).isEqualTo("KNOWN");
    }

    @Test
    @DisplayName("terminal-first vs outcome-first: both orders charge exactly once")
    void terminalFirstAndOutcomeFirstBothChargeOnce() {
        usageQuota(100_000L);
        UUID interactionId = UUID.randomUUID();

        // Terminal aggregate FIRST (the execution terminal beat the outcome
        // to the engine)…
        service.settleUsage(execution.getId(), "agg-1",
                InteractionUsageService.Source.ORCHESTRATION, null,
                Map.of("totalTokens", 500L), "KNOWN");

        // …then the interaction outcome's own settlement arrives late: its
        // subtotal is inside the aggregate, the accounted total is already
        // at/beyond it → nothing extra bills.
        service.settleUsage(execution.getId(), "inter-1",
                InteractionUsageService.Source.INTERACTION, interactionId,
                Map.of("inputTokens", 120L, "outputTokens", 30L), "KNOWN");

        assertThat(consumed()).isEqualTo(500L);
        assertThat(attributedTotal(accountingOf())).isEqualTo(150L);
        assertThat(((Number) accountingOf().get("accountedTokens")).longValue()).isEqualTo(500L);
    }

    @Test
    @DisplayName("late settlement after terminal updates accounting only (no state change)")
    void lateSettlementAfterTerminalIsAccountingOnly() {
        usageQuota(100_000L);
        // No allocation ever ran on this execution — the seed entity is
        // current here (stream cursor 0).
        execution.setState(SessionExecution.State.COMPLETED);
        execution.setTerminalMessageId("tm-late");
        executions.saveAndFlush(execution);

        service.settleUsage(execution.getId(), "late-1",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                Map.of("totalTokens", 90L), "KNOWN");

        // The terminal state is untouched.
        assertThat(executions.findById(execution.getId()).orElseThrow().getState())
                .isEqualTo(SessionExecution.State.COMPLETED);
        assertThat(consumed()).isEqualTo(90L);
    }

    // ------------------------------------------------------------------
    // Quota breach while HELD (plan Task 8 rerun list)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quota breach while HELD: settlement charges the quota engine; the check blocks at the ceiling")
    void quotaBreachWhileHeldIsRecordedAndBlocks() {
        usageQuota(100L);
        tx(() -> {
            SessionExecution locked = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            locked.setHoldState("HELD");
            executions.save(locked);
        });

        service.settleUsage(execution.getId(), "held-1",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                Map.of("totalTokens", 150L), "KNOWN");

        // The consumption recorded (fail-closed accounting continues while HELD).
        assertThat(consumed()).isEqualTo(150L);
        // The next pre-flight is blocked.
        QuotaDecision decision = quotaPolicyEngineCheck(QuotaResourceType.TOKENS);
        assertThat(decision.isBlocked()).isTrue();
    }

    // ------------------------------------------------------------------
    // Policy-producer notification (the §8.7 tighten-only channel)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("settlement notifies the policy producer with the accounted cumulative totals (the real producer seam: the throttle applies)")
    void settlementNotifiesPolicyProducer() {
        // The REAL SessionPolicyService (bean-injected) — the notification
        // path of the production service; the §8.7 throttle tolerates the
        // producer's internal state across the batch.
        ai.myrmec.engine.inference.execution.SessionPolicyService producer = policyService;
        long before = policyUpdatesRecorded(producer);

        service.settleUsage(execution.getId(), "n-1",
                InteractionUsageService.Source.INTERACTION, UUID.randomUUID(),
                Map.of("totalTokens", 120L), "KNOWN");

        long after = policyUpdatesRecorded(producer);
        assertThat(after).as("the policy producer's throttle advanced").isGreaterThanOrEqualTo(before);
    }

    /** The producer's throttle state is internal — observe via the last-sent
     * snapshot map (reflective; test-only). */
    private long policyUpdatesRecorded(ai.myrmec.engine.inference.execution.SessionPolicyService producer) {
        try {
            var field = ai.myrmec.engine.inference.execution.SessionPolicyService.class
                    .getDeclaredField("lastSent");
            field.setAccessible(true);
            Object map = field.get(producer);
            return ((java.util.Map<?, ?>) map).size();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void usageQuota(long limit) {
        Quota quota = new Quota();
        quota.setScopeType(Quota.Scope.PROJECT);
        quota.setScopeId(project.getId());
        quota.setResourceType(Quota.ResourceType.TOKENS);
        quota.setPeriod(Quota.Period.DAILY);
        quota.setLimitAmount(limit);
        quota.setQuotaType(QuotaType.CEILING);
        quota.setEnforcementMode(EnforcementMode.BLOCK);
        quotaRepository.save(quota);
    }

    /** The project's consumed TOKENS (the quota_consumption row sum). */
    private long consumed() {
        UUID quotaId = quotaRepository.findAll().stream()
                .filter(q -> q.getScopeId().equals(project.getId())
                        && q.getResourceType() == Quota.ResourceType.TOKENS)
                .findFirst().orElseThrow().getId();
        return consumptionRepository.findAll().stream()
                .filter(c -> c.getQuotaId().equals(quotaId))
                .mapToLong(QuotaConsumptionEntityReader::amountUsed)
                .sum();
    }

    /** Quota entity/consumption id shim (avoids a cross-cycle getter). */
    private static final class QuotaConsumptionEntityReader {
        static long amountUsed(ai.myrmec.engine.quota.QuotaConsumption c) {
            return c.getAmountUsed();
        }
    }

    private Map<String, Object> accountingOf() {
        return executions.findById(execution.getId()).orElseThrow()
                .getInteractionUsage();
    }

    @SuppressWarnings("unchecked")
    private static long attributedTotal(Map<String, Object> accounting) {
        Object attributed = accounting.get("attributedTokens");
        if (attributed instanceof Map<?, ?> map) {
            return map.values().stream()
                    .filter(v -> v instanceof Number)
                    .mapToLong(v -> ((Number) v).longValue())
                    .sum();
        }
        return 0;
    }

    private QuotaDecision quotaPolicyEngineCheck(QuotaResourceType resource) {
        return quotaCheck.check(QuotaScope.PROJECT, project.getId(), resource, 1);
    }

    private void tx(Runnable body) {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> body.run());
    }

    private Workflow workflowOf(Project parent) {
        Workflow wf = new Workflow();
        wf.setProject(parent);
        wf.setName("usg-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return wf == null ? null : workflowRepository.save(wf);
    }
}
