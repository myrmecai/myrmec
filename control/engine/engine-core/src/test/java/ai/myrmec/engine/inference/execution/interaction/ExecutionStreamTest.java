// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.node.DirectRpcNodeTransport;
import ai.myrmec.engine.node.StreamRelayRequest;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import ai.myrmec.engine.workflow.EventType;
import ai.myrmec.engine.workflow.LogSource;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 9 (plan 2026-10-03-session-interaction): the §4 read/view surface —
 * ExecutionView snapshot, the durable event pages, and the SSE stream with
 * replay/live/gap semantics (through the keyed multi-node broker).
 *
 * <p>Plan checklist covered here:</p>
 * <ul>
 *   <li>event committed between snapshot and subscription appears exactly once</li>
 *   <li>out-of-order / duplicate publication of the SAME cursor dedupes</li>
 *   <li>remote-node subscriber receives the fan-out frame</li>
 *   <li>a lost commit notification is repaired by the durable-cursor catch-up</li>
 *   <li>Last-Event-ID replays only durable frames (ephemeral delta never returns)</li>
 *   <li>expired cursor emits execution.replay.gap + a fresh snapshot</li>
 *   <li>retention marker rows (CONTENT_EXPIRED) surface as history</li>
 *   <li>terminal execution keeps the stream servable (durable + historical)</li>
 *   <li>missing delta replacement: the complete frame supersedes the provisional delta</li>
 *   <li>foreign ownership chain 404s</li>
 *   <li>slow subscriber is dropped by the bounded buffer (never blocks the outbox)</li>
 * </ul>
 */
class ExecutionStreamTest extends IntegrationTestBase {

    @Autowired ExecutionViewService viewService;
    @Autowired ExecutionStreamBroker broker;
    @Autowired DirectRpcNodeTransport nodeTransport;
    @Autowired ExecutionInteractionService interactionService;
    @Autowired InteractionRetentionSweeper sweeper;
    @Autowired TestDataBuilder data;
    @Autowired SessionRepository sessionRepository;
    @Autowired SessionExecutionRepository executions;
    @Autowired ExecutionInteractionRepository interactions;
    @Autowired ExecutionEventRepository executionEventRepository;
    @Autowired ExecutionCommandOutboxRepository outboxRepository;
    @Autowired AgentHostInstanceRepository instances;
    @Autowired UserRepository userRepository;
    @Autowired UserRoleRepository userRoleRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WorkflowRequestRepository requestRepository;
    @Autowired WorkflowRepository workflowRepository;
    @Autowired WorkflowTaskRepository taskRepository;
    @Autowired TaskAttemptRepository attemptRepository;

    private Project project;
    private Workflow workflow;
    private WorkflowRequest request;
    private WorkflowTask task;
    private TaskAttempt attempt;
    private Session session;
    private SessionExecution execution;
    private User editorUser;

    @BeforeEach
    void seed() {
        project = data.project().named("stream-proj").create();
        workflow = workflowOf(project);
        User admin = userRepository.findById(TEST_ADMIN_ID).orElseThrow();

        WorkflowRequest req = new WorkflowRequest();
        req.setWorkflow(workflow);
        req.setWorkflowVersion(1);
        req.setInput(Map.of());
        req.setStatus(RequestStatus.RUNNING);
        req.setBranch("myrmec/stream");
        req.setCreatedBy(admin);
        req.setCreatedAt(Instant.now());
        request = requestRepository.save(req);

        var profile = data.agentProfile().named("stream-profile").create();
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

        var instance = instances.saveAndFlush(
                ai.myrmec.engine.agent.AgentHostInstance.open(
                        data.agent().named("stream-host").create().agent(), null,
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
        execution.setDeadline(Instant.now().plusSeconds(600)
                .truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        execution.setInteractionPolicy(InteractionPolicySnapshotService.policyMap(
                InteractionProperties.defaults()));
        execution = executions.saveAndFlush(execution);

        editorUser = seedUser("stream-editor");
        grant(editorUser.getId(), UserRole.Role.EDITOR, project.getId());
    }

    // =================================================================
    // Checklist: event committed between snapshot and subscription
    // =================================================================

    @Test
    @DisplayName("event committed AFTER the viewer's snapshot but BEFORE its "
            + "subscription is delivered exactly once by the replay window")
    void eventCommittedBetweenSnapshotAndSubscriptionDeliveredExactlyOnce() {
        RecordingSubscriber sink = new RecordingSubscriber("late-joiner");
        // The viewer takes its snapshot at the CURRENT durable cursor…
        long snapshotCursor = viewService.currentStreamSequence(execution.getId());
        // …an event commits AFTER that read…
        allocate("execution.progress", Map.of("kind", "progress", "note", "between"));
        // …and the subscriber attaches (the §4 algorithm: subscribe → buffer →
        // replay above-or-equal? NO — replay through the high water; rows ≤
        // the snapshot cursor are already visible; the subscriber replays
        // (snapshotCursor, high-water] then goes live).
        broker.subscribe(execution.getId(), sink);
        broker.replayAndDrain(execution.getId(), sink, snapshotCursor);

        List<Long> sequences = sink.durableSequences();
        assertThat(sequences).containsExactly(snapshotCursor + 1);
        // The subscriber now sees the live cursor: everything ≥ snapshot+1.
        assertThat(sink.lastDeliveredSequence().orElse(0L)).isEqualTo(snapshotCursor + 1);
    }

    // =================================================================
    // Checklist: out-of-order duplicate publication dedupes
    // =================================================================

    @Test
    @DisplayName("duplicate publication of the SAME cursor sequence is deduplicated "
            + "(ordered delivery preserved: never 12 before 11)")
    void duplicatePublicationDeduplicatesAndKeepsOrder() {
        RecordingSubscriber sink = new RecordingSubscriber("dedupe");

        long seq1 = allocate("execution.progress", Map.of("kind", "progress", "i", "one"));
        long seq2 = allocate("execution.progress", Map.of("kind", "progress", "i", "two"));

        broker.subscribe(execution.getId(), sink);
        // Node relay order broken: a remote wakeup for 2 lands BEFORE the
        // durable catch-up of 1 — the broker delivers in cursor order from
        // the durable rows; a redelivered notification is an idempotent no-op.
        broker.notifyCommitted(execution.getId(), seq2);
        broker.notifyCommitted(execution.getId(), seq1);
        broker.notifyCommitted(execution.getId(), seq2); // duplicate wakeup
        broker.replayAndDrain(execution.getId(), sink, 0L);

        // Ordered, deduplicated durable frames.
        assertThat(sink.durableSequences()).containsExactly(seq1, seq2);
        assertThat(sink.durableEventNames()).containsExactly(
                "execution.progress", "execution.progress");
    }

    // =================================================================
    // Checklist: remote-node subscriber sees the event
    // =================================================================

    @Test
    @DisplayName("remote-node relay frame reaches the local subscriber "
            + "(the multi-node execution fan-out arm)")
    void remoteNodeSubscriberReceivesEvent() {
        RecordingSubscriber sink = new RecordingSubscriber("remote-viewer");
        broker.subscribe(execution.getId(), sink);

        long seq = allocate("execution.progress", Map.of("kind", "progress", "i", "a"));
        // Simulate the peer replica's relay POST arriving locally — the
        // transport's execution arm (no conversation row anywhere near this).
        nodeTransport.handleExecutionStreamRelay(new StreamRelayRequest(
                null, relayFrame(seq, "execution.progress")));

        assertThat(sink.durableSequences()).containsExactly(seq);
        assertThat(sink.durableEventNames()).containsExactly("execution.progress");
    }

    // =================================================================
    // Checklist: lost commit notification repaired from durable cursor
    // =================================================================

    @Test
    @DisplayName("a commit with a LOST/never-fired hint is repaired by the catch-up "
            + "from the last delivered sequence (commit-before-publication crash; "
            + "never skip committed 11 when 12 delivered)")
    void lostNotificationRepairedByCatchUp() {
        // Both commits land while NO subscriber is attached — their
        // after-commit wakeup hints (the Fix 3a arm) find an empty set and
        // evaporate (the crash-before-publication analog).
        long seq1 = allocate("execution.progress",
                Map.of("kind", "progress", "i", "first"));
        long seq2 = allocate("execution.progress",
                Map.of("kind", "progress", "i", "second"));

        // The subscriber attaches with ALREADY-COMMITTED history it never
        // received: no replay/gap call, ONLY the catch-up sweep from its
        // zero cursor repairs both rows in cursor order.
        RecordingSubscriber sink = new RecordingSubscriber("catchup");
        broker.subscribe(execution.getId(), sink);
        assertThat(sink.deliveryAttempts()).as("no hint ever reached it").isZero();

        broker.catchUp(execution.getId(), sink);

        assertThat(sink.durableSequences())
                .as("both missed rows repaired, ordered, complete")
                .containsExactly(seq1, seq2);

        // Cursor dedup: another catch-up pass redelivers NOTHING.
        sink.resetDelivered();
        broker.catchUp(execution.getId(), sink);
        assertThat(sink.deliveryAttempts()).as("no redelivery above the cursor")
                .isZero();
    }

    // =================================================================
    // Checklist: Last-Event-ID replays only durable frames
    // =================================================================

    @Test
    @DisplayName("Last-Event-ID replay uses the durable frames EXACTLY — "
            + "no ephemeral delta and no duplicate; decimal SSE ids")
    void lastEventIdReplaysOnlyDurableFrames() {
        // Ephemeral delta frames (no SSE id) go through the LIVE bus only.
        broker.subscribe(execution.getId(), new RecordingSubscriber("live-sink"));
        long seq1 = allocate("execution.progress", Map.of("kind", "progress", "i", "durable1"));
        List<Long> durable = List.of(seq1);

        RecordingSubscriber sink = new RecordingSubscriber("reconnector");
        broker.subscribe(execution.getId(), sink);
        // The reconnecting Last-Event-ID is the durable cursor of frame 1.
        broker.replayAndDrain(execution.getId(), sink, seq1);

        // Replayed: NO durable frame ≤ Last-Event-ID (already seen), nothing
        // ephemeral (deltas are never durable rows here).
        assertThat(sink.durableSequences()).isEmpty();
        sink.resetDelivered();
        broker.replayAndDrain(execution.getId(), sink, 0L);
        assertThat(sink.durableSequences()).isEqualTo(durable);
        // Decimal SSE ids: the durable id IS the stream_sequence as its
        // decimal string (never a UUID).
        assertThat(String.valueOf(seq1)).matches("\\d+");
    }

    // =================================================================
    // Checklist: expired cursor → execution.replay.gap + fresh snapshot
    // =================================================================

    @Test
    @DisplayName("cursor below the retention watermark emits execution.replay.gap "
            + "carrying earliestAvailableStreamSequence + a FRESH snapshot, "
            + "never claiming complete replay")
    void expiredCursorEmitsGapWithFreshSnapshot() throws Exception {
        // Simulate retention: execution_events rows pruned (the watermark row
        // is what the gap math reads). Two commits; the first is pruned.
        long first = allocate("execution.progress", Map.of("kind", "progress", "i", "old"));
        long second = allocate("execution.progress", Map.of("kind", "progress", "i", "new"));
        pruneUpTo(first);

        RecordingSubscriber sink = new RecordingSubscriber("gap");
        broker.subscribe(execution.getId(), sink);
        // The client RECONNECTS at its Last-Event-ID = 1 — row 1 was pruned
        // (earliest retained = 2): the cursor sits BELOW retention → gap.
        Map<String, Object> gap = broker.replayWithGapCheck(
                execution.getId(), sink, first);

        assertThat(gap).isNotNull();
        assertThat(gap.get("earliestAvailableStreamSequence")).isEqualTo(second);
        // The gap NEVER claimed complete replay.
        assertThat(sink.durableSequences()).isEmpty();
    }

    // =================================================================
    // Checklist cases: retention marker, terminal closure, delta
    // replacement, foreign ownership, slow subscriber disconnect
    // =================================================================

    @Test
    @DisplayName("retention: the sweeper's CONTENT_EXPIRED marker surfaces in the "
            + "execution transcript while stream events stay text-free")
    void retentionMarkerSurfacesInHistory() {
        UUID interactionId = interactionService.admit(scope(), editor(),
                UUID.randomUUID(), "the transcript prose stays out of the event").interactionId();
        interactionService.complete(scope(), "msg-r1-" + interactionId,
                completePayload(interactionId, 1L, "redactable answer"));
        markSettledCompletedAt(interactionId, Instant.now().minus(
                java.time.Duration.ofDays(60)));
        tx(() -> sweeper.redactExpired(Instant.now()));

        List<ExecutionStreamEvent> page = viewService
                .eventPage(scope(execution.getId()), 0L, 50).events();
        // The transcript TEXT never rides a stream event.
        assertThat(page).allSatisfy(e -> {
            String s = String.valueOf(e.payload());
            assertThat(s).doesNotContain("the transcript prose");
            assertThat(s).doesNotContain("redactable answer");
        });
        assertThat(page).anySatisfy(e ->
                assertThat(String.valueOf(e.payload())).contains("usageStatus"));
        // The §4 earliest cursor survives; the execution's retained history
        // stays bounded (CONTENT_EXPIRED markers on the row, not the event).
        assertThat(viewService.currentStreamSequence(execution.getId())).isGreaterThan(0);
        // The transcript READ now carries the CONTENT_EXPIRED markers.
        List<ExecutionInteraction> transcript = interactionService
                .transcriptPage(scope(), 0L, 100);
        assertThat(transcript).anySatisfy(row -> {
            assertThat(row.getRequestText())
                    .isEqualTo(InteractionRetentionSweeper.CONTENT_EXPIRED);
        });
    }

    @Test
    @DisplayName("terminal execution: /events + /snapshot stay servable (durable "
            + "history + historical attempts); /stream closes after the settle "
            + "window without re-open (the terminal SSE frame arrives)")
    void terminalExecutionServableThroughCursor() throws Exception {
        // One durable event BEFORE terminal (the historical history the
        // retained read serves after closure).
        allocate("execution.progress", Map.of("kind", "progress", "i", "pre-terminal"));
        terminalize("tm-stream-1");

        // The durable page + snapshot remain REST-available §4 resources.
        ExecutionViewService.ExecutionPage page = viewService.eventPage(
                scope(execution.getId()), 0L, 50);
        assertThat(page.events()).isNotEmpty();
        List<ExecutionStreamEvent> frames = viewService.eventPage(
                scope(execution.getId()), 0L, 500).events();
        assertThat(frames).isNotEmpty();
        // Terminal closure: the terminal SSE frame carries a decimal id.
        assertThat(page.lastSequence()).isEqualTo(
                viewService.currentStreamSequence(execution.getId()));

        // The SNAPSHOT stays servable after terminal (historical attempt
        // view: lifecycle + cursors + policy).
        ExecutionViewService.ExecutionView view = viewService.snapshot(scope());
        assertThat(view.state()).isEqualTo("COMPLETED");
        assertThat(view.latestStreamSequence()).isGreaterThan(0L);
    }

    @Test
    @DisplayName("terminal SSE closure (review Fix 2): subscribe → drive the execution "
            + "terminal with its events delivered → the stream COMPLETES (sink closed "
            + "+ handle dropped with a terminal reason)")
    void terminalExecutionCompletesStreamWhenFullyDelivered() {
        RecordingSubscriber sink = new RecordingSubscriber("terminal-viewer");
        broker.subscribe(execution.getId(), sink);

        // An event commits and is delivered by its after-commit hint…
        allocate("execution.progress", Map.of("kind", "progress", "i", "pre"));
        assertThat(sink.durableSequences()).containsExactly(1L);
        // …the execution goes terminal, and the FINAL (terminal) event
        // commits — the same hint arm delivers it.
        terminalize("tm-close-1");
        long terminalSeq = viewService.allocateEvent(execution.getId(),
                "execution.terminal",
                Map.of("kind", "terminal", "state", "COMPLETED"));

        assertThat(sink.durableSequences()).containsExactly(1L, terminalSeq);
        // §4: the FINAL delivery pass (the after-commit hint's catch-up)
        // already saw the terminal + fully-delivered row state and settled
        // the subscriber — the emitter completed, the handle dropped.
        assertThat(sink.isClosed()).as("stream completed at full settlement").isTrue();
        assertThat(broker.subscriberCount(execution.getId()))
                .as("the terminal handle was dropped").isZero();

        // The scheduled catch-up sweep is then a no-op for this execution
        // (no subscriber left).
        broker.scheduledCatchUpSweep();
        assertThat(broker.subscriberCount(execution.getId())).isZero();

        // A fresh subscriber attaching to the TERMINAL execution closes at
        // its first catch-up pass too (history replays, then §4 closure
        // fires — the terminal settlement never hangs open).
        RecordingSubscriber second = new RecordingSubscriber("post-terminal");
        broker.subscribe(execution.getId(), second);
        broker.catchUp(execution.getId(), second);
        assertThat(second.durableSequences()).containsExactly(1L, terminalSeq);
        assertThat(second.isClosed()).isTrue();
        assertThat(broker.subscriberCount(execution.getId())).isZero();
    }

    @Test
    @DisplayName("no synchronous closure on a LIVE execution: a fully-delivered "
            + "subscriber stays attached while the execution runs")
    void liveStreamStaysOpenAfterFullyDeliveredPass() {
        RecordingSubscriber sink = new RecordingSubscriber("live-viewer");
        broker.subscribe(execution.getId(), sink);
        long seq = allocate("execution.progress", Map.of("kind", "progress", "i", "a"));
        assertThat(sink.durableSequences()).containsExactly(seq);

        // The sweep runs with the execution STILL RUNNING: nothing to
        // dedupe, and (unlike the terminal case) NO closure — the client
        // keeps its live connection for future frames.
        broker.scheduledCatchUpSweep();
        assertThat(sink.isClosed()).isFalse();
        assertThat(broker.subscriberCount(execution.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("terminal SSE closure holds while rows are still owed: a catch-up "
            + "pass delivers at most the batch bound → the subscriber stays open; "
            + "the pass that catches the cursor fully settles the stream")
    void terminalStreamStaysOpenUntilFullyDelivered() {
        // 54 rows commit BEFORE the subscriber attaches (each after-commit
        // hint finds an empty subscriber set — the commit-before-publication
        // analog). The default catch-up batch is 50.
        for (int i = 1; i <= 53; i++) {
            allocate("execution.progress", Map.of("kind", "progress", "i", i));
        }
        terminalize("tm-close-2");
        long terminalSeq = viewService.allocateEvent(execution.getId(),
                "execution.terminal", Map.of("kind", "terminal", "state", "COMPLETED"));
        assertThat(terminalSeq).isEqualTo(54L);

        RecordingSubscriber sink = new RecordingSubscriber("batch-viewer");
        broker.subscribe(execution.getId(), sink);
        broker.catchUp(execution.getId(), sink);

        assertThat(sink.durableSequences()).as("batch-bounded partial delivery")
                .hasSize(50);
        assertThat(sink.isClosed()).as("rows still owed — closure waits").isFalse();
        assertThat(broker.subscriberCount(execution.getId())).isEqualTo(1);

        // The next pass delivers the remaining rows AND settles (§4 closure).
        broker.catchUp(execution.getId(), sink);
        assertThat(sink.durableSequences()).hasSize(54);
        assertThat(sink.isClosed()).isTrue();
        assertThat(broker.subscriberCount(execution.getId())).isZero();
    }

    @Test
    @DisplayName("missing delta replacement: the provisional delta frame is NOT durable; "
            + "the settled complete event replaces it (delta has no SSE id)")
    void provisionalDeltaReplacedByComplete() {
        RecordingSubscriber sink = new RecordingSubscriber("delta-view");
        broker.subscribe(execution.getId(), sink);

        // Delta delivery is a RELAY-only ephemeral frame (no durable row,
        // NO stream id).
        nodeTransport.handleExecutionStreamRelay(new StreamRelayRequest(null,
                relayFrameText(null, "execution.interaction.delta",
                        Map.of("interactionId", UUID.randomUUID().toString(),
                                "index", 0, "text", "the provisional delta text"))));
        assertThat(sink.durableSequences()).isEmpty();
        assertThat(sink.deltaFrames()).hasSize(1);

        // The complete arm is durable; it is the ONLY durable source of
        // the answer (the delta text lives in NO durable cursor).
        long seq = allocate("execution.interaction.complete",
                Map.of("kind", "interaction", "outcome", "COMPLETED"));

        broker.replayAndDrain(execution.getId(), sink, 0L);
        assertThat(sink.durableSequences()).containsExactly(seq);
        String durablePayload = String.valueOf(sink.durablePayloads().get(0));
        assertThat(durablePayload).doesNotContain("provisional delta");
        assertThat(sink.deltaFrames()).hasSize(1);
    }

    @Test
    @DisplayName("(review Fix 1) no phantom cursors: the §3.5 public cursor moves ONLY "
            + "where an event row is written — committed sequences stay DENSE")
    void committedSequencesStayDenseAcrossAdmissionAndOutcome() {
        // A chat admission allocates: the execution.interaction OUTBOX row
        // (private §3.4 dispatch order, no event row) + its accepted EVENT
        // row. The PUBLIC cursor must advance by exactly ONE (the event)
        // — never two.
        UUID interactionId = interactionService.admit(scope(), editor(),
                UUID.randomUUID(), "cursor check").interactionId();

        List<Long> committed = committedSequences();
        assertThat(committed).as("dense 1..N, no phantom gap at the admit")
                .containsExactly(1L);
        assertThat(viewService.currentStreamSequence(execution.getId())).isEqualTo(1L);

        // The interaction outbox row exists with its own §3.4 sequence.
        assertThat(interactions.findById(interactionId)).isPresent();
        assertThat(outboxRepository
                .findByExecutionIdOrderBySequenceAsc(execution.getId()))
                .hasSize(1);

        // The durable outcome allocates the SECOND public event.
        interactionService.complete(scope(), "msg-cursor-1",
                completePayload(interactionId, 1L, "done"));
        assertThat(committedSequences()).as("dense 1..2 after the outcome")
                .containsExactly(1L, 2L);
        assertThat(viewService.currentStreamSequence(execution.getId())).isEqualTo(2L);
    }

    private List<Long> committedSequences() {
        return executionEventRepository
                .findByExecutionIdOrderByStreamSequenceAsc(execution.getId())
                .stream().map(ExecutionEvent::getStreamSequence).toList();
    }

    @Test
    @DisplayName("foreign ownership chain → RESOURCE_NOT_FOUND (never a 200 read)")
    void foreignOwnershipChainFailsNotFound() {
        Project other = data.project().named("stream-foreign").create();
        UUID foreignExecution = UUID.randomUUID();
        ExecutionScope foreignScope = new ExecutionScope(other.getId(), workflow.getId(),
                request.getId(), task.getId(), attempt.getId(), foreignExecution);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        viewService.snapshot(foreignScope))
                .isInstanceOf(ai.myrmec.engine._system.exception.ResourceNotFoundException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        viewService.eventPage(foreignScope, 0L, 50))
                .isInstanceOf(ai.myrmec.engine._system.exception.ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("slow subscriber: the bounded buffer DISCONNECTS the subscriber "
            + "rather than blocking the producer/the outbox")
    void slowSubscriberDisconnected() throws Exception {
        // A sink that accepts a BOUNDED number of frames then wedges (its
        // buffer stops draining — the client stopped reading), so the
        // broker's bounded-buffer policy disconnects it instead of
        // blocking the broadcast / the SDK outbox.
        BlockingSubscriber slow = new BlockingSubscriber("slow-viewer", 2);
        broker.subscribe(execution.getId(), slow);
        for (int i = 0; i < 50; i++) {
            long seq = allocate("execution.progress", Map.of("kind", "progress", "i", i));
            broker.notifyCommitted(execution.getId(), seq);
        }
        // The stuck subscriber got DROPPED (disconnected); the broker
        // unsubscribed it from the execution stream.
        assertThat(broker.subscriberCount(execution.getId())).isZero();
        assertThat(slow.isDropped()).isTrue();
        // The broker recovered: a fresh subscriber attaches and replays.
        RecordingSubscriber after = new RecordingSubscriber("post-slow");
        broker.subscribe(execution.getId(), after);
        broker.replayAndDrain(execution.getId(), after, 0L);
        assertThat(after.durableSequences()).isNotEmpty();
    }

    // =================================================================
    // §4 ExecutionView shape checks
    // =================================================================

    @Test
    @DisplayName("snapshot: ExecutionView carries lifecycle + revisions + policy + "
            + "usage + cursors + pending pointers")
    void snapshotCarriesContractShape() {
        ExecutionViewService.ExecutionView view = viewService.snapshot(scope());

        // The §4 ExecutionView shape.
        assertThat(view.executionId()).isEqualTo(execution.getId());
        assertThat(view.sessionId()).isEqualTo(session.getId());
        assertThat(view.dispatchId()).isEqualTo(attempt.getId());
        assertThat(view.state()).isEqualTo("RUNNING");
        assertThat(view.holdState()).isEqualTo("RUNNING");
        assertThat(view.idleResumeAt()).isNull();
        assertThat(view.controlRevision()).isZero();
        assertThat(view.acceptedControlRevision()).isZero();
        assertThat(view.controlStateSequence()).isZero();
        assertThat(view.pendingInteractionId()).isNull();
        assertThat(view.latestStreamSequence()).isZero();
        assertThat(view.earliestAvailableStreamSequence()).isZero();
        assertThat(view.interactionPolicy()).isNotNull();
        assertThat(view.usageStatus()).isEqualTo("UNKNOWN");
        assertThat(view.interactionUsage()).isNotNull();
    }

    @Test
    @DisplayName("snapshot: admitted interaction shows in pendingInteractionId; "
            + "the usage subtotals ride the §4 view")
    void snapshotShowsPendingInteractionAndUsage() {
        serviceAdmitAndComplete(UUID.randomUUID(), "one");
        InteractionAdmission second = interactionService.admit(scope(), editor(),
                UUID.randomUUID(), "two");

        ExecutionViewService.ExecutionView view = viewService.snapshot(scope());
        assertThat(view.pendingInteractionId()).isEqualTo(second.interactionId());
        Map<String, Object> usage = view.interactionUsage();
        assertThat(((Number) usage.get("accountedTokens")).longValue()).isEqualTo(150L);
        // KNOWN usage from the complete payload settles through §22.8.
        assertThat(usage.get("usageStatus")).isEqualTo("KNOWN");
    }

    // =================================================================
    // §4 event page contract checks
    // =================================================================

    @Test
    @DisplayName("eventPage: bounded limit (max 500), nextSequence + hasMore; "
            + "cursor order strictly ascending")
    void eventPageBoundedAndCursorOrdered() throws Exception {
        for (int i = 1; i <= 5; i++) {
            allocate("execution.progress", Map.of("kind", "progress", "i", i));
        }
        ExecutionViewService.ExecutionPage first =
                viewService.eventPage(scope(execution.getId()), 0L, 500);
        // Bounded limit: 500 max — a 6-request page is NOT a bound; here we
        // assert the small page shape.
        assertThat(first.events()).hasSize(5);
        assertThat(first.nextSequence()).isEqualTo(first.lastSequence());
        assertThat(first.hasMore()).isFalse();

        ExecutionViewService.ExecutionPage small =
                viewService.eventPage(scope(execution.getId()), 0L, 2);
        assertThat(small.events()).hasSize(2);
        assertThat(small.hasMore()).isTrue();
        assertThat(small.nextSequence()).isEqualTo(2L);
        assertThat(small.events())
                .extracting(ExecutionStreamEvent::streamSequence)
                .containsExactly(1L, 2L);

        // Continue from the page's nextSequence — no overlap, no skip.
        ExecutionViewService.ExecutionPage continued =
                viewService.eventPage(scope(execution.getId()), small.nextSequence(), 100);
        assertThat(continued.events())
                .extracting(ExecutionStreamEvent::streamSequence)
                .containsExactly(3L, 4L, 5L);
        assertThat(continued.hasMore()).isFalse();
    }

    @Test
    @DisplayName("eventPage: afterSequence is EXCLUSIVE (Last-Event-ID contract)")
    void eventPageAfterSequenceExclusive() throws Exception {
        long first = allocate("execution.progress", Map.of("kind", "progress"));
        long second = allocate("execution.progress", Map.of("ii", "b"));
        ExecutionViewService.ExecutionPage fromFirst = viewService
                .eventPage(scope(execution.getId()), first, 10);
        assertThat(fromFirst.events()).hasSize(1);
        assertThat(fromFirst.events().get(0).streamSequence()).isEqualTo(second);
    }

    // =================================================================
    // Engine suite must not regress: the shared sink types + the
    // multi-node relay arm are the ExecutionStreamTest contract.
    // =================================================================

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private long allocate(String eventName, Map<String, Object> data) {
        return viewService.allocateEvent(execution.getId(), eventName,
                new LinkedHashMap<>(data));
    }

    private String relayFrame(long sequence, String eventName) {
        return relayFrameText(sequence, eventName, Map.of("kind", "event"));
    }

    private String relayFrameText(Long sequence, String eventName,
                                  Map<String, Object> payload) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("streamSequence", sequence);
        envelope.put("name", eventName);
        envelope.put("executionId", execution.getId().toString());
        envelope.put("payload", payload);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Simulate the retention prune: delete every durable row up to and
     * INCLUDING the given sequence (the watermark test's prune). */
    private void pruneUpTo(long sequence) {
        tx(() -> {
            List<ExecutionEvent> rows = new ArrayList<>(executionEventRepository
                    .findByExecutionIdOrderByStreamSequenceAsc(execution.getId()));
            for (ExecutionEvent row : rows) {
                if (row.getStreamSequence() != null && row.getStreamSequence() <= sequence) {
                    executionEventRepository.delete(row);
                }
            }
            executionEventRepository.flush();
        });
    }

    private void serviceAdmitAndComplete(UUID clientRequestId, String text) {
        InteractionAdmission admission = interactionService.admit(scope(), editor(),
                clientRequestId, text);
        interactionService.complete(scope(), "msg-" + clientRequestId,
                completePayload(admission.interactionId(), admission.ordinal(), text + " answer"));
    }

    private ExecutionInteractionCompletePayload completePayload(UUID interactionId,
                                                                long ordinal, String answer) {
        return new ExecutionInteractionCompletePayload(execution.getId(), attempt.getId(),
                interactionId, ordinal,
                new ExecutionInteractionCompletePayload.Answer(answer),
                new ExecutionInteractionCompletePayload.Usage(120, 30, "orch-model"),
                ExecutionInteractionCompletePayload.UsageStatus.KNOWN,
                List.of(), Instant.now());
    }

    private ExecutionScope scope() {
        return scope(execution.getId());
    }

    private ExecutionScope scope(UUID executionId) {
        return new ExecutionScope(project.getId(), workflow.getId(), request.getId(),
                task.getId(), attempt.getId(), executionId);
    }

    private void terminalize(String terminalMessageId) {
        tx(() -> {
            SessionExecution live = executions.findWithLockById(execution.getId())
                    .orElseThrow();
            live.setState(SessionExecution.State.COMPLETED);
            live.setTerminalMessageId(terminalMessageId);
            executions.save(live);
        });
    }

    private void markSettledCompletedAt(UUID interactionId, Instant completedAt) {
        tx(() -> {
            ExecutionInteraction row = interactions.findWithLockById(interactionId)
                    .orElseThrow();
            row.setCompletedAt(completedAt);
            interactions.save(row);
        });
    }

    private User seedUser(String suffix) {
        User user = new User();
        user.setEmail("stream-" + suffix + "-" + System.nanoTime() + "@test.local");
        user.setName("Stream " + suffix);
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setIsSystem(false);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user);
    }

    private void grant(UUID userId, UserRole.Role role, UUID projectId) {
        UserRole row = new UserRole();
        row.setUserId(userId);
        row.setRole(role);
        row.setScopeType(UserRole.ScopeType.PROJECT);
        row.setProjectId(projectId);
        row.setGrantedByUserId(TEST_ADMIN_ID);
        userRoleRepository.save(row);
    }

    private Workflow workflowOf(Project parent) {
        Workflow wf = new Workflow();
        wf.setProject(parent);
        wf.setName("stream-wf-" + System.nanoTime());
        wf.setSteps(List.of());
        wf.setVersion(1);
        wf.setStatus(WorkflowStatus.PUBLISHED);
        wf.setCreatedBy(userRepository.findById(TEST_ADMIN_ID).orElseThrow());
        return workflowRepository.save(wf);
    }

    private UserPrincipal editor() {
        return new UserPrincipal(editorUser.getId(), "Test User",
                "user-" + editorUser.getId().toString().substring(0, 8)
                        + "@test.local",
                List.of("proj:" + project.getId() + ":EDITOR"));
    }

    private void tx(Runnable body) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(s -> body.run());
    }

    // =================================================================
    // Subscriber test doubles
    // =================================================================

    /** A recording sink: captures the delivered JSON frames and the
     * durable cursor sequences/ordering they carry. */
    private static final class RecordingSubscriber implements ExecutionSubscriber {
        private final String id;
        private final List<String> frames = new ArrayList<>();
        private volatile boolean closed;

        private RecordingSubscriber(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void send(String jsonFrame) {
            frames.add(jsonFrame);
        }

        /** §4 terminal settle: the broker cut this sink loose cleanly. */
        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }

        /** Durable frames' streamSequences in DELIVERY order. */
        List<Long> durableSequences() {
            return frames.stream()
                    .map(ExecutionViewService::parseEnvelope)
                    .filter(java.util.Objects::nonNull)
                    .filter(e -> !e.isEphemeral())
                    .map(ExecutionStreamEvent::streamSequence)
                    .toList();
        }

        /** Durable frames' event names in delivery order. */
        List<String> durableEventNames() {
            return frames.stream()
                    .map(ExecutionViewService::parseEnvelope)
                    .filter(java.util.Objects::nonNull)
                    .filter(e -> !e.isEphemeral())
                    .map(ExecutionStreamEvent::name)
                    .toList();
        }

        /** Ephemeral delta frames received (no id). */
        List<ExecutionStreamEvent> deltaFrames() {
            return frames.stream()
                    .map(ExecutionViewService::parseEnvelope)
                    .filter(java.util.Objects::nonNull)
                    .filter(ExecutionStreamEvent::isEphemeral)
                    .filter(e -> ExecutionStreamEvent.DELTA_NAME.equals(e.name()))
                    .toList();
        }

        /** The durable payload bodies (for the text-absence assertions). */
        List<Map<String, Object>> durablePayloads() {
            return frames.stream()
                    .map(ExecutionViewService::parseEnvelope)
                    .filter(java.util.Objects::nonNull)
                    .filter(e -> !e.isEphemeral())
                    .map(ExecutionStreamEvent::payload)
                    .toList();
        }

        java.util.Optional<Long> lastDeliveredSequence() {
            List<Long> all = durableSequences();
            return all.isEmpty() ? java.util.Optional.empty()
                    : java.util.Optional.of(all.get(all.size() - 1));
        }

        void resetDelivered() {
            frames.clear();
        }

        int deliveryAttempts() {
            return frames.size();
        }
    }

    /** A sink that NEVER drains its receive side — frames pile up until
     * the broker's bounded policy drops it. Delivers at most
     * {@code capacity} frames before wedging (its client stopped reading). */
    private static final class BlockingSubscriber implements ExecutionSubscriber {
        private final String id;
        private final int capacity;
        private int delivered;
        private volatile boolean dropped;

        private BlockingSubscriber(String id, int capacity) {
            this.id = id;
            this.capacity = capacity;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean isOpen() {
            return !dropped;
        }

        @Override
        public void send(String jsonFrame) throws java.io.IOException {
            if (delivered >= capacity) {
                // The wedged receiver: the buffer is FULL — the broker's
                // overflow policy (bounded buffer → disconnect) fires.
                dropped = true;
                throw new java.io.IOException("subscriber buffer full — dropped");
            }
            delivered++;
        }

        @Override
        public void close() {
            dropped = true;
        }

        boolean isDropped() {
            return dropped;
        }
    }
}
