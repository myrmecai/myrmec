// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import ai.myrmec.engine.workflow.EventType;
import ai.myrmec.engine.workflow.LogSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 2 persistence layer (plan §3, session-interaction): the three new
 * tables, the session_executions/execution_events extensions, the
 * repository contracts, unique keys, UTC round trips, existing-row
 * defaults, and the §3.2 admission invariant — two concurrent contenders
 * on the execution row lock leave exactly one pending interaction.
 */
class InteractionPersistenceTest extends IntegrationTestBase {

    @Autowired TestDataBuilder data;
    @Autowired SessionExecutionRepository executions;
    @Autowired ExecutionInteractionRepository interactions;
    @Autowired ExecutionControlRequestRepository controlRequests;
    @Autowired ExecutionCommandOutboxRepository outboxRepository;
    @Autowired ExecutionEventRepository executionEventRepository;
    @Autowired SessionRepository sessionRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired InteractionPolicySnapshotService policySnapshotService;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A session + a RUNNING execution row (the admission lock owner). */
    private SessionExecution runningExecution() {
        var project = data.project().named("ip-proj").create();
        var session = new ai.myrmec.engine.inference.Session();
        session.setServiceType("CONVERSATION");
        session.setRefId(UUID.randomUUID());
        session.setProjectId(project.getId());
        session.setStatus("ACTIVE");
        session.setAllocationState("ACTIVE");
        session = sessionRepository.saveAndFlush(session);

        var execution = new SessionExecution();
        execution.setSessionId(session.getId());
        execution.setServiceType("CONVERSATION");
        execution.setState(SessionExecution.State.RUNNING);
        execution.setCreatedAt(Instant.now());
        return executions.saveAndFlush(execution);
    }

    private ExecutionInteraction newPendingInteraction(UUID executionId, long ordinal, String text) {
        ExecutionInteraction interaction = pendingWithoutSave(executionId, ordinal, text);
        return interactions.saveAndFlush(interaction);
    }

    /** Build a pending interaction WITHOUT persisting (callers save). */
    private ExecutionInteraction pendingWithoutSave(UUID executionId, long ordinal, String text) {
        ExecutionInteraction interaction = new ExecutionInteraction();
        interaction.setExecutionId(executionId);
        interaction.setOrdinal(ordinal);
        interaction.setActorUserId(UUID.randomUUID());
        interaction.setClientRequestId(UUID.randomUUID());
        interaction.setRequestDigest(("d" + ordinal).repeat(32).substring(0, 64));
        interaction.setStatus(InteractionStatus.ACCEPTED);
        interaction.setRequestText(text);
        interaction.setResponseDeadline(Instant.now().plusSeconds(120).truncatedTo(
                java.time.temporal.ChronoUnit.MILLIS));
        interaction.setAcceptedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        interaction.setUsageStatus("UNKNOWN");
        return interaction;
    }

    // ------------------------------------------------------------------
    // §3.1 columns: UTC round trips + fresh-row initialization
    // ------------------------------------------------------------------

    @Test
    @DisplayName("session_executions gains §3.1 columns with UTC instant round trips")
    void sessionExecutionColumnsRoundTripUtc() {
        SessionExecution execution = runningExecution();
        Instant before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        execution.setControlRevision(7L);
        execution.setAcceptedControlRevision(5L);
        execution.setControlStateSequence(3L);
        execution.setHoldState("HELD");
        execution.setHoldChangedAt(before);
        execution.setIdleResumeAt(before.plusSeconds(300));
        execution.setNextInteractionOrdinal(4L);
        execution.setStreamSequence(42L);
        executions.saveAndFlush(execution);

        SessionExecution reloaded = executions.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getControlRevision()).isEqualTo(7L);
        assertThat(reloaded.getAcceptedControlRevision()).isEqualTo(5L);
        assertThat(reloaded.getControlStateSequence()).isEqualTo(3L);
        assertThat(reloaded.getHoldState()).isEqualTo("HELD");
        assertThat(reloaded.getHoldChangedAt()).isEqualTo(before);
        assertThat(reloaded.getIdleResumeAt()).isEqualTo(before.plusSeconds(300));
        assertThat(reloaded.getNextInteractionOrdinal()).isEqualTo(4L);
        assertThat(reloaded.getStreamSequence()).isEqualTo(42L);
    }

    @Test
    @DisplayName("fresh execution rows initialize the §3.1 defaults through @PrePersist")
    void freshExecutionRowsInitializeDefaults() {
        SessionExecution execution = runningExecution();

        SessionExecution fresh = executions.findById(execution.getId()).orElseThrow();
        assertThat(fresh.getControlRevision()).isZero();
        assertThat(fresh.getAcceptedControlRevision()).isZero();
        assertThat(fresh.getControlStateSequence()).isZero();
        assertThat(fresh.getHoldState()).isEqualTo("RUNNING");
        assertThat(fresh.getNextInteractionOrdinal()).isEqualTo(1L);
        assertThat(fresh.getStreamSequence()).isZero();
        assertThat(fresh.getPendingInteractionId()).isNull();
        assertThat(fresh.getInteractionPolicy()).isNull();
        assertThat(fresh.getInteractionUsage()).isNull();
    }

    @Test
    @DisplayName("policy + usage snapshots round trip through the JSONB columns")
    void interactionPolicyAndUsageSnapshotsRoundTripAsJson() {
        SessionExecution execution = runningExecution();
        Map<String, Object> policy = new HashMap<>();
        policy.put("version", 1);
        policy.put("enabled", true);
        policy.put("idleResumeAfterSeconds", 300);
        policy.put("responseTimeoutSeconds", 120);
        policy.put("maxInputBytes", 16384);
        policy.put("maxOutputBytes", 65536);
        policy.put("maxModelIterations", 8);
        policy.put("maxHistoryBytes", 262144);
        policy.put("transcriptRetentionDays", 30);
        policy.put("contentMode", "USER_CHAT_ONLY");
        execution.setInteractionPolicy(policy);
        execution.setInteractionUsage(Map.of("promptTokens", 12L, "completionTokens", 5L));
        executions.saveAndFlush(execution);

        SessionExecution reloaded = executions.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getInteractionPolicy()).isNotNull();
        assertThat(reloaded.getInteractionPolicy().get("contentMode")).isEqualTo("USER_CHAT_ONLY");
        assertThat(((Number) reloaded.getInteractionPolicy().get("responseTimeoutSeconds")).intValue())
                .isEqualTo(120);
        assertThat(reloaded.getInteractionUsage()).containsEntry("promptTokens", 12);
    }

    /**
     * §3.1: the migration defaults for PRE-EXISTING rows hold — the physical
     * columns exist, are NOT NULL where designed, and carry the schema
     * default values.
     */
    @Test
    @DisplayName("migration: new NOT NULL columns exist with defaults on the physical schema")
    void migrationColumnsExistWithSchemaDefaults() {
        runningExecution();

        // H2 stores identifiers UPPERCASE in information_schema (verified by
        // probe); PostgreSQL keeps the created lowercase form. Compare
        // case-insensitively to keep the check portable across both.
        List<Object[]> cols = jdbcTemplate.query("""
                SELECT column_name, upper(data_type), column_default
                FROM information_schema.columns
                WHERE lower(table_name) = 'session_executions'
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3)});

        Map<String, Object[]> byName = new HashMap<>();
        for (Object[] row : cols) {
            byName.put(String.valueOf(row[0]).toLowerCase(), row);
        }
        assertThat(byName).containsKeys("control_revision", "accepted_control_revision",
                "control_state_sequence", "hold_state", "next_interaction_ordinal",
                "pending_interaction_id", "interaction_policy", "interaction_usage",
                "stream_sequence");
        assertThat(String.valueOf(byName.get("control_revision")[1])).isEqualTo("BIGINT");
        assertThat(String.valueOf(byName.get("hold_state")[1])).contains("CHARACTER");
        assertThat(String.valueOf(byName.get("interaction_policy")[1])).isIn("JSONB", "JSON", "CLOB", "BLOB");
        // NOT NULL with explicit DEFAULT for the counter columns.
        for (String col : List.of("control_revision", "accepted_control_revision",
                "control_state_sequence", "next_interaction_ordinal", "stream_sequence")) {
            Object[] c = byName.get(col);
            assertThat(String.valueOf(c[2]))
                    .as("column %s must carry its migration default", col)
                    .isNotBlank();
        }
    }

    // ------------------------------------------------------------------
    // §3.2 admission invariant: one pending interaction per execution
    // via the pessimistic-write execution row lock
    // ------------------------------------------------------------------

    @Test
    @DisplayName("second contender under the execution row lock observes the pending pointer and is refused")
    void secondContenderSeesPendingPointerUnderRowLock() {
        SessionExecution execution = runningExecution();
        UUID executionId = execution.getId();

        // Contender 1 (committed): takes the row lock, admits, sets the pending pointer.
        TransactionTemplate first = new TransactionTemplate(transactionManager);
        first.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        first.executeWithoutResult(status -> {
            SessionExecution locked = executions.findWithLockById(executionId).orElseThrow();
            ExecutionInteraction admitted = newPendingInteraction(executionId, 1L, "first");
            locked.setPendingInteractionId(admitted.getId());
            executions.saveAndFlush(locked);
        });

        // Contender 2: takes the row lock AFTER contender 1 committed, sees
        // the pointer, and must admit NOTHING (409 RESOURCE_IN_USE). The
        // row lock serializes the check-then-act pair — the structure the
        // Task 6 admission service drives with the same template.
        TransactionTemplate contender = new TransactionTemplate(transactionManager);
        contender.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        Integer admittedBySecond = contender.execute(tx -> {
            SessionExecution locked = executions.findWithLockById(executionId).orElseThrow();
            if (locked.getPendingInteractionId() != null) {
                return 0;   // 409 RESOURCE_IN_USE: chat already pending
            }
            newPendingInteraction(executionId, 2L, "second");
            locked.setPendingInteractionId(UUID.randomUUID());
            executions.saveAndFlush(locked);
            return 1;
        });

        assertThat(admittedBySecond).as("the second contender must be refused").isZero();
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(executionId)).hasSize(1);
        assertThat(executions.findById(executionId).orElseThrow().getPendingInteractionId()).isNotNull();

        // After §3.2 settlement the pointer clears and admission is possible again.
        TransactionTemplate settle = new TransactionTemplate(transactionManager);
        settle.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        settle.executeWithoutResult(tx -> {
            SessionExecution locked = executions.findWithLockById(executionId).orElseThrow();
            ExecutionInteraction row = interactions
                    .findByExecutionIdOrderByOrdinalAsc(executionId).get(0);
            row = interactions.findWithLockById(row.getId()).orElseThrow();
            row.setStatus(InteractionStatus.COMPLETED);
            row.setAnswerText("done");
            row.setCompletedAt(Instant.now());
            row.setUsageStatus("KNOWN");
            interactions.saveAndFlush(row);
            locked.setPendingInteractionId(null);
            executions.saveAndFlush(locked);
        });
        assertThat(executions.findById(executionId).orElseThrow().getPendingInteractionId()).isNull();
        var outcome = interactions.findByExecutionIdOrderByOrdinalAsc(executionId).get(0);
        assertThat(outcome.getStatus()).isEqualTo(InteractionStatus.COMPLETED);
        assertThat(interactions.findByExecutionIdAndStatus(executionId, InteractionStatus.ACCEPTED))
                .isEmpty();
    }

    /** §3.2: a completion clears the pending pointer transactionally. */
    @Test
    @DisplayName("completing the pending interaction clears the pointer and records the outcome")
    void completingInteractionClearsPendingPointer() {
        SessionExecution execution = runningExecution();
        UUID executionId = execution.getId();
        ExecutionInteraction interaction = newPendingInteraction(executionId, 1L, "answer me");

        // Arrange INSIDE a committed REQUIRES_NEW transaction (the admitted
        // interaction IS the pending pointer — same shape as the race
        // winner block). The earlier no-op detached-copy variant could never
        // fail, because the pointer was never committed.
        TransactionTemplate arrange = new TransactionTemplate(transactionManager);
        arrange.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        arrange.executeWithoutResult(tx -> {
            SessionExecution locked = executions.findWithLockById(executionId).orElseThrow();
            locked.setPendingInteractionId(interaction.getId());
            executions.saveAndFlush(locked);
        });
        assertThat(executions.findById(executionId).orElseThrow().getPendingInteractionId())
                .as("sanity: the pending pointer must be committed before settlement")
                .isEqualTo(interaction.getId());

        // Settlement (mirrors production §3.2 completion): load the execution
        // WITH the row lock, clear the pointer, save.
        TransactionTemplate settle = new TransactionTemplate(transactionManager);
        settle.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        settle.executeWithoutResult(tx -> {
            SessionExecution locked = executions.findWithLockById(executionId).orElseThrow();
            ExecutionInteraction row = interactions.findWithLockById(interaction.getId()).orElseThrow();
            row.setStatus(InteractionStatus.COMPLETED);
            row.setAnswerText("The verifier is checking the current candidate.");
            row.setCompletedAt(Instant.now());
            row.setUsageStatus("KNOWN");
            interactions.saveAndFlush(row);
            locked.setPendingInteractionId(null);
            executions.saveAndFlush(locked);
        });

        ExecutionInteraction settled = interactions.findById(interaction.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(InteractionStatus.COMPLETED);
        assertThat(settled.getUsageStatus()).isEqualTo("KNOWN");
        assertThat(executions.findById(executionId).orElseThrow().getPendingInteractionId()).isNull();
    }

    /**
     * TRUE two-thread race (house SessionAllocatorTest pattern): both
     * contenders race the SAME check-then-act pair on the execution row
     * lock; the lock serializes them so exactly one wins.
     */
    @Test
    @DisplayName("two racing engine contenders cannot both admit chat (row-lock serialization)")
    void twoRacingContendersCannotBothAdmitChat() throws Exception {
        SessionExecution execution = runningExecution();
        UUID executionId = execution.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();

        Runnable contender = () -> {
            try {
                ready.countDown();
                release.await();
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                Boolean won = tx.execute(status -> {
                    SessionExecution locked =
                            executions.findWithLockById(executionId).orElseThrow();
                    if (locked.getPendingInteractionId() != null) {
                        return false;   // 409 RESOURCE_IN_USE: chat already pending
                    }
                    newPendingInteraction(executionId, 1L, "race");
                    locked.setPendingInteractionId(interactions
                            .findByExecutionIdOrderByOrdinalAsc(executionId).get(0).getId());
                    executions.saveAndFlush(locked);
                    return true;
                });
                if (Boolean.TRUE.equals(won)) {
                    wins.incrementAndGet();
                }
            } catch (Exception e) {
                // lock timeout / rollback counts as a loss
            }
        };

        try {
            pool.submit(contender);
            pool.submit(contender);
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(wins.get())
                .as("exactly one contender admits; the row lock serializes them")
                .isEqualTo(1);
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(executionId)).hasSize(1);
    }

    // ------------------------------------------------------------------
    // §3.2 unique keys: ordinal + (execution, actor, clientRequestId)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("duplicate ordinal fails once; duplicate (execution, actor, clientRequestId) fails")
    void duplicateOrdinalAndClientRequestIdsFail() {
        SessionExecution execution = runningExecution();
        UUID actor = UUID.randomUUID();
        UUID clientRequestId = UUID.randomUUID();

        // Seed the committed row: build the entity WITHOUT saving (the
        // newPendingInteraction helper saves eagerly), then persist once.
        ExecutionInteraction first = new ExecutionInteraction();
        first.setExecutionId(execution.getId());
        first.setOrdinal(1L);
        first.setActorUserId(actor);
        first.setClientRequestId(clientRequestId);
        first.setRequestDigest(("d1").repeat(32));
        first.setStatus(InteractionStatus.ACCEPTED);
        first.setRequestText("same text");
        first.setResponseDeadline(Instant.now().plusSeconds(120)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        first.setAcceptedAt(Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        first.setUsageStatus("UNKNOWN");
        interactions.saveAndFlush(first);

        // (a) a second interaction on the same execution + ordinal violates
        // unique (execution_id, ordinal).
        assertThatThrownBy(() -> interactions.saveAndFlush(
                pendingWithoutSave(execution.getId(), 1L, "different actor, same ordinal slot")))
                .as("(execution_id, ordinal) must be unique")
                .isInstanceOf(DataIntegrityViolationException.class);

        // (b) the same actor + clientRequestId re-admitted violates the
        // idempotency key.
        ExecutionInteraction sameClientRequest =
                pendingWithoutSave(execution.getId(), 2L, "same text");
        sameClientRequest.setActorUserId(actor);
        sameClientRequest.setClientRequestId(clientRequestId);
        assertThatThrownBy(() -> interactions.saveAndFlush(sameClientRequest))
                .as("(execution_id, actor_user_id, client_request_id) must be unique")
                .isInstanceOf(DataIntegrityViolationException.class);

        // (c) the same ordinal for a DIFFERENT execution is fine: the key
        // is per-execution.
        SessionExecution other = runningExecution();
        assertThat(interactions.saveAndFlush(
                newPendingInteraction(other.getId(), 1L, "other execution")).getId()).isNotNull();

        // The original row survived both failed inserts (the violations
        // rolled back in their own transactions).
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId())).hasSize(1);
    }

    /** §3.2: terminal_message_id is a unique key; NULLs coexist freely. */
    @Test
    @DisplayName("duplicate terminal_message_id fails; multiple NULL terminal_message_ids coexist")
    void duplicateTerminalMessageIdFailsAndNullsCoexist() {
        SessionExecution execution = runningExecution();
        String terminalMessageId = "tmq-" + UUID.randomUUID();

        // First terminal record claims the wire messageId.
        ExecutionInteraction first =
                pendingWithoutSave(execution.getId(), 1L, "first chat");
        first.setTerminalMessageId(terminalMessageId);
        interactions.saveAndFlush(first);

        // A second interaction completing with the SAME terminal
        // messageId violates unique terminal_message_id (different
        // ordinal, so only the terminal key can be the violator).
        ExecutionInteraction duplicate =
                pendingWithoutSave(execution.getId(), 2L, "second chat");
        duplicate.setTerminalMessageId(terminalMessageId);
        assertThatThrownBy(() -> interactions.saveAndFlush(duplicate))
                .as("terminal_message_id must be unique (§3.2)")
                .isInstanceOf(DataIntegrityViolationException.class);

        // Multiple NULL terminal message ids coexist: mid-flight rows
        // (no terminal frame yet) collide with nothing.
        assertThat(interactions.saveAndFlush(
                pendingWithoutSave(execution.getId(), 3L, "in flight")).getId()).isNotNull();
        assertThat(interactions.saveAndFlush(
                pendingWithoutSave(execution.getId(), 4L, "also in flight")).getId()).isNotNull();

        // The first row survived the failed duplicate insert (the violation
        // rolled back in its own transaction; the ordinal-2 duplicate is
        // gone, so only ordinals 1, 3, 4 remain).
        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId()))
                .extracting(ExecutionInteraction::getTerminalMessageId)
                .containsExactly(terminalMessageId, null, null);
    }

    // ------------------------------------------------------------------
    // §3.4 rollback removes command intent + outbox together
    // ------------------------------------------------------------------

    @Test
    @DisplayName("rolling back the admission transaction removes the outbox entry too")
    void rollbackRemovesCommandIntentAndOutboxTogether() {
        SessionExecution execution = runningExecution();

        TransactionTemplate atomicUnit = new TransactionTemplate(transactionManager);
        atomicUnit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThatThrownBy(() -> atomicUnit.executeWithoutResult(tx -> {
            ExecutionInteraction interaction = newPendingInteraction(execution.getId(), 1L,
                    "hold my attempt");

            ExecutionControlRequest intent = new ExecutionControlRequest();
            intent.setExecutionId(execution.getId());
            intent.setInteractionId(interaction.getId());
            intent.setActorUserId(interaction.getActorUserId());
            intent.setAction("HOLD");
            intent.setOrigin("CHAT");
            intent.setRequestDigest("1".repeat(64));
            intent.setStatus(InteractionControlStatus.ACCEPTED);
            intent.setResolutionRevision(1L);
            intent.setControlRevision(1L);
            intent.setCommandMessageId("cmd-" + UUID.randomUUID());
            intent.setCreatedAt(Instant.now());
            controlRequests.saveAndFlush(intent);

            ExecutionCommandOutbox entry = new ExecutionCommandOutbox();
            // §3.4: the row id IS the wire messageId — assigned.
            entry.setId(UUID.randomUUID());
            entry.setExecutionId(execution.getId());
            entry.setSessionId(execution.getSessionId());
            entry.setHostInstanceId(UUID.randomUUID());
            entry.setType("execution.control");
            entry.setCorrelationId(intent.getCommandMessageId());
            entry.setSequence(1L);
            entry.setEnvelope(Map.of("type", "execution.control",
                    "messageId", intent.getCommandMessageId()));
            entry.setPayloadDigest("2".repeat(64));
            entry.setStatus(OutboxStatus.PENDING);
            entry.setExpiresAt(Instant.now().plusSeconds(60)
                    .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            entry.setNextDeliveryAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
            entry.setDeliveryCount(0);
            entry.setCreatedAt(Instant.now());
            outboxRepository.saveAndFlush(entry);

            // The caller's exception rolls the WHOLE unit back — admission,
            // intent, and the dispatch entry vanish together (§3.4).
            throw new IllegalStateException("simulate dispatch/side-effect failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(interactions.findByExecutionIdOrderByOrdinalAsc(execution.getId())).isEmpty();
        assertThat(controlRequests.findByExecutionIdOrderByCreatedAtAsc(execution.getId())).isEmpty();
        assertThat(outboxRepository.findByExecutionIdOrderBySequenceAsc(execution.getId())).isEmpty();
    }

    // ------------------------------------------------------------------
    // §3.3 unique key + §22.7 status vocabulary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("duplicate (execution, actor, clientRequestId) control intents fail; statuses round trip")
    void controlRequestUniquenessAndStatusRoundTrip() {
        SessionExecution execution = runningExecution();
        UUID actor = UUID.randomUUID();

        ExecutionControlRequest first = new ExecutionControlRequest();
        first.setExecutionId(execution.getId());
        first.setActorUserId(actor);
        first.setAction("HOLD");
        first.setOrigin("BUTTON");
        first.setClientRequestId(UUID.randomUUID());
        first.setRequestDigest("3".repeat(64));
        first.setStatus(InteractionControlStatus.PENDING);
        first.setResolutionRevision(0L);
        first.setCreatedAt(Instant.now());
        first = controlRequests.saveAndFlush(first);

        ExecutionControlRequest duplicate = new ExecutionControlRequest();
        duplicate.setExecutionId(execution.getId());
        duplicate.setActorUserId(actor);
        duplicate.setAction("HOLD");
        duplicate.setOrigin("BUTTON");
        duplicate.setClientRequestId(first.getClientRequestId());
        duplicate.setRequestDigest("4".repeat(64));
        duplicate.setStatus(InteractionControlStatus.PENDING);
        duplicate.setResolutionRevision(0L);
        duplicate.setCreatedAt(Instant.now());
        assertThatThrownBy(() -> controlRequests.saveAndFlush(duplicate))
                .as("(execution_id, actor_user_id, client_request_id) must be unique for control requests")
                .isInstanceOf(DataIntegrityViolationException.class);

        // §22.7 dispositions round trip exactly.
        for (InteractionControlStatus status : List.of(
                InteractionControlStatus.ACCEPTED,
                InteractionControlStatus.CONFIRMATION_REQUIRED,
                InteractionControlStatus.REJECTED,
                InteractionControlStatus.DECLINED,
                InteractionControlStatus.EXPIRED)) {
            first.setStatus(status);
            controlRequests.saveAndFlush(first);
            assertThat(controlRequests.findById(first.getId()).orElseThrow().getStatus())
                    .isEqualTo(status);
        }
    }

    @Test
    @DisplayName("control request: PENDING vocabulary and the confirmation window round trip")
    void controlRequestConfirmationWindowRoundTrip() {
        SessionExecution execution = runningExecution();
        ExecutionControlRequest proposal = new ExecutionControlRequest();
        proposal.setExecutionId(execution.getId());
        proposal.setActorUserId(UUID.randomUUID());
        proposal.setAction("CANCEL");
        proposal.setOrigin("CHAT");
        proposal.setRequestDigest("5".repeat(64));
        proposal.setStatus(InteractionControlStatus.CONFIRMATION_REQUIRED);
        proposal.setResolutionRevision(1L);
        proposal.setConfirmationExpiresAt(Instant.now().plusSeconds(60)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        proposal.setExplanation("User asked to stop this attempt.");
        proposal.setCreatedAt(Instant.now());
        proposal = controlRequests.saveAndFlush(proposal);

        ExecutionControlRequest reloaded = controlRequests.findById(proposal.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(InteractionControlStatus.CONFIRMATION_REQUIRED);
        assertThat(reloaded.getAction()).isEqualTo("CANCEL");
        assertThat(reloaded.getConfirmationExpiresAt()).isNotNull();
        assertThat(reloaded.getCommandMessageId()).as("decline/expiry creates no command").isNull();
    }

    /**
     * §3.3: interaction_id is a real FK — dangling references are refused.
     * Negative-only: the integration cleanup (IntegrationTestBase) deletes
     * session_executions and lets cascades clear children in an H2-chosen
     * order, so a COMMITTED valid control request here would trip the
     * non-cascading interaction FK in the next test's cleanup. The H2
     * constraint name in the failure proves the FK is the violator.
     */
    @Test
    @DisplayName("control request with a dangling interaction_id violates the §3.3 FK")
    void controlRequestDanglingInteractionIdFails() {
        SessionExecution execution = runningExecution();

        ExecutionControlRequest dangling = new ExecutionControlRequest();
        dangling.setExecutionId(execution.getId());
        dangling.setInteractionId(UUID.randomUUID());
        dangling.setActorUserId(UUID.randomUUID());
        dangling.setAction("HOLD");
        dangling.setOrigin("CHAT");
        dangling.setRequestDigest("6".repeat(64));
        dangling.setStatus(InteractionControlStatus.ACCEPTED);
        dangling.setResolutionRevision(1L);
        dangling.setCreatedAt(Instant.now());
        assertThatThrownBy(() -> controlRequests.saveAndFlush(dangling))
                .as("interaction_id must be an FK to execution_interactions (§3.3)")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("FK_EXECUTION_CONTROL_REQUESTS_INTERACTION");
    }

    // ------------------------------------------------------------------
    // §3.4 outbox: due PENDING, ack by messageId, terminal expiry
    // ------------------------------------------------------------------

    @Test
    @DisplayName("outbox: due PENDING query, ack by messageId, terminal expiry")
    void outboxDueAckAndExpire() {
        SessionExecution execution = runningExecution();
        UUID hostInstanceId = UUID.randomUUID();

        ExecutionCommandOutbox due = outboxEntry(execution, hostInstanceId, 1L,
                Instant.now().minusSeconds(1));
        ExecutionCommandOutbox notDue = outboxEntry(execution, hostInstanceId, 2L,
                Instant.now().plusSeconds(60));
        ExecutionCommandOutbox acked = outboxEntry(execution, hostInstanceId, 3L,
                Instant.now().minusSeconds(1));
        ExecutionCommandOutbox stale = outboxEntry(execution, hostInstanceId, 4L,
                Instant.now().minusSeconds(1));
        stale.setStatus(OutboxStatus.EXPIRED);
        outboxRepository.save(stale);

        // ACK `acked` BEFORE the due query — an acked entry is due by clock
        // but excluded by status PENDING. It must flip in its own
        // transaction (a @Modifying update owns one in production too).
        TransactionTemplate acksBeforeQuery = new TransactionTemplate(transactionManager);
        acksBeforeQuery.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        acksBeforeQuery.executeWithoutResult(tx ->
                outboxRepository.ackByMessageId(acked.getId(), Instant.now()));

        var duePending = outboxRepository.findDuePending(Instant.now());
        assertThat(duePending).extracting(ExecutionCommandOutbox::getId).containsExactly(due.getId());

        // ACK by messageId flips PENDING -> ACKED; a @Modifying update runs
        // inside its own transaction here (the service owns one in prod).
        TransactionTemplate acks = new TransactionTemplate(transactionManager);
        acks.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        acks.executeWithoutResult(tx -> {
            outboxRepository.ackByMessageId(acked.getId(), Instant.now());
            outboxRepository.ackByMessageId(due.getId(), Instant.now());
            outboxRepository.expirePendingForExecution(execution.getId());
        });
        assertThat(outboxRepository.findById(due.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.ACKED);
        assertThat(outboxRepository.findById(due.getId()).orElseThrow().getAcknowledgedAt())
                .isNotNull();

        // A terminal execution expires undispatched PENDING commands.
        assertThat(outboxRepository.findById(notDue.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.EXPIRED);
        assertThat(outboxRepository.findById(acked.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.ACKED);
    }

    private ExecutionCommandOutbox outboxEntry(SessionExecution execution, UUID hostInstanceId,
                                               long sequence, Instant nextDeliveryAt) {
        ExecutionCommandOutbox entry = new ExecutionCommandOutbox();
        // §3.4: the row id IS the wire messageId — assigned, never generated.
        entry.setId(UUID.randomUUID());
        entry.setExecutionId(execution.getId());
        entry.setSessionId(execution.getSessionId());
        entry.setHostInstanceId(hostInstanceId);
        entry.setType("execution.control");
        entry.setSequence(sequence);
        entry.setEnvelope(Map.of("type", "execution.control", "sequence", sequence));
        entry.setPayloadDigest(("d" + sequence).repeat(32).substring(0, 64));
        entry.setStatus(OutboxStatus.PENDING);
        entry.setExpiresAt(Instant.now().plusSeconds(60)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        entry.setNextDeliveryAt(nextDeliveryAt != null
                ? nextDeliveryAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS) : null);
        entry.setDeliveryCount(0);
        entry.setCreatedAt(Instant.now());
        return outboxRepository.saveAndFlush(entry);
    }

    // ------------------------------------------------------------------
    // §3.5 execution_events extensions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("execution_events gains nullable execution_id + stream_sequence with a unique pair")
    void executionEventsGainsExecutionIdAndStreamSequence() {
        SessionExecution execution = runningExecution();

        ExecutionEvent engineEvent = new ExecutionEvent();
        engineEvent.setEventType(EventType.ORCHESTRATION);
        engineEvent.setExecutionId(execution.getId());
        engineEvent.setStreamSequence(1L);
        engineEvent.setKind("WORKFLOW");
        engineEvent.setSource(LogSource.TASK);
        engineEvent.setMessage("cursor event");
        executionEventRepository.saveAndFlush(engineEvent);

        // A second event with the same (execution, stream_sequence) violates
        // the unique cursor pair.
        ExecutionEvent duplicate = new ExecutionEvent();
        duplicate.setEventType(EventType.ORCHESTRATION);
        duplicate.setExecutionId(execution.getId());
        duplicate.setStreamSequence(1L);
        duplicate.setKind("WORKFLOW");
        duplicate.setSource(LogSource.TASK);
        duplicate.setMessage("same cursor");
        assertThatThrownBy(() -> executionEventRepository.saveAndFlush(duplicate))
                .as("(execution_id, stream_sequence) must be unique")
                .isInstanceOf(DataIntegrityViolationException.class);

        // The broker cursor query resolves by (execution, stream_sequence).
        assertThat(executionEventRepository
                .findByExecutionIdAndStreamSequence(execution.getId(), 1L))
                .isPresent();

        // Historical rows without an execution keep execution_id +
        // stream_sequence NULL (a plain unique index permits multiple NULLs
        // in H2 + PostgreSQL).
        ExecutionEvent legacy = new ExecutionEvent();
        legacy.setEventType(EventType.LOG);
        legacy.setLogLevel("INFO");
        legacy.setMessage("pre-interaction historical row");
        legacy.setKind("WORKFLOW");
        legacy.setSource(LogSource.TASK);
        executionEventRepository.saveAndFlush(legacy);
        ExecutionEvent legacyReloaded = executionEventRepository
                .findById(legacy.getId()).orElseThrow();
        assertThat(legacyReloaded.getStreamSequence()).isNull();
        assertThat(legacyReloaded.getExecutionId()).isNull();
    }

    @Test
    @DisplayName("migration: execution_events gains the execution_id + stream_sequence columns")
    void migrationColumnsExistOnExecutionEvents() {
        runningExecution();

        // H2 uppercase identifiers; compare case-insensitively (probe note).
        List<Object[]> cols = jdbcTemplate.query("""
                SELECT column_name, upper(data_type)
                FROM information_schema.columns
                WHERE lower(table_name) = 'execution_events'
                """, (rs, i) -> new Object[]{rs.getString(1), rs.getString(2)});

        Map<String, String> byName = new HashMap<>();
        for (Object[] row : cols) {
            byName.put(String.valueOf(row[0]).toLowerCase(), String.valueOf(row[1]));
        }
        assertThat(byName).containsKeys("execution_id", "stream_sequence");
        assertThat(byName.get("stream_sequence")).isEqualTo("BIGINT");
    }

    // ------------------------------------------------------------------
    // §3.1 policy-snapshot wiring (SessionContextAssembler)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("orchestration initialization persists the effective policy snapshot on the execution")
    void orchestrationPersistenceWiringPersistsEffectivePolicy() {
        SessionExecution execution = runningExecution();
        policySnapshotService.persistOrchestrationPolicy(execution.getId(),
                InteractionProperties.defaults());

        var reloaded = executions.findById(execution.getId()).orElseThrow();
        assertThat(reloaded.getInteractionPolicy()).isNotNull();
        assertThat(((Number) reloaded.getInteractionPolicy().get("version")).intValue()).isEqualTo(1);
        assertThat(reloaded.getInteractionPolicy().get("contentMode")).isEqualTo("USER_CHAT_ONLY");
        assertThat(reloaded.getInteractionPolicy().get("responseTimeoutSeconds")).isEqualTo(120);
    }

    @Test
    @DisplayName("orchestration initialization rejects a missing policy (fail-closed)")
    void orchestrationPersistenceWiringRejectsMissingPolicy() {
        SessionExecution execution = runningExecution();
        assertThatThrownBy(() -> policySnapshotService.persistOrchestrationPolicy(
                execution.getId(), null))
                .as("missing orchestration policy must fail closed")
                .isInstanceOf(IllegalStateException.class);
        assertThat(executions.findById(execution.getId()).orElseThrow().getInteractionPolicy())
                .as("no snapshot is written for a rejected policy")
                .isNull();
    }

    // ------------------------------------------------------------------
    // §3.2 service-layer admission return shape (fixed record)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("InteractionAdmission record carries the fixed shape")
    void interactionAdmissionRecordShape() {
        Instant deadline = Instant.now().plusSeconds(60);
        var admission = new InteractionAdmission(UUID.randomUUID(), 3L,
                InteractionStatus.ACCEPTED, deadline);
        assertThat(admission.interactionId()).isNotNull();
        assertThat(admission.ordinal()).isEqualTo(3L);
        assertThat(admission.status()).isEqualTo(InteractionStatus.ACCEPTED);
        assertThat(admission.responseDeadline()).isEqualTo(deadline);
    }
}

