// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentHostInstance;
import ai.myrmec.engine.agent.AgentHostInstanceRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.ExecutionCommandSender;
import ai.myrmec.engine.inference.execution.ExecutionInputAssembler;
import ai.myrmec.engine.inference.execution.ExecutionRegistry;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.websocket.host.HostControlWebSocketHandler;
import ai.myrmec.engine.websocket.host.payload.ExecutionStartPayload;
import ai.myrmec.engine.websocket.host.payload.OrchestrationExecutionStartPayload;
import ai.myrmec.engine.websocket.message.payload.SessionOpenPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Resumes a parked workflow dispatch once its session is allocation-ACTIVE
 * (protocol &sect;7.3/&sect;7.4) &mdash; the workflow counterpart of the
 * conversation handler's staged-turn continuation.
 *
 * <p>Two continuation points, driven by
 * {@link HostControlWebSocketHandler}:</p>
 * <ol>
 *   <li>{@code session.accept} &rarr; {@link #onSessionAccepted}: ship the
 *       assembled {@code session.open}. For an ORCHESTRATOR step the
 *       &sect;16.2 assignment rides that frame; for ordinary inference it is the
 *       &sect;6.1 context only.</li>
 *   <li>{@code session.opened} &rarr; {@link #onSessionOpened}: the session is
 *       ACTIVE &mdash; mint the execution row, ship {@code execution.start}, and
 *       bind the engine-authored attempt to the worker the allocator minted for
 *       the serving slot.</li>
 * </ol>
 *
 * <p><b>Exactly-once.</b> The staged dispatch is consumed on
 * {@code session.opened}, so a replayed opened frame cannot start a second
 * execution (&sect;11.3.5 makes it illegal anyway); the attempt row is created
 * once, at dispatch time, and is never recreated here.</p>
 *
 * <p>The handler dependency is {@code @Lazy}: handler &rarr; continuation &rarr;
 * handler would otherwise be an unresolvable constructor-injection cycle.</p>
 */
@Slf4j
@Service
public class TaskDispatchContinuation {

    private final PendingTaskDispatches pendingTaskDispatches;
    private final HostControlWebSocketHandler hostControlWebSocketHandler;
    private final SessionRepository sessionRepository;
    private final SessionContextAssembler sessionContextAssembler;
    private final ExecutionRegistry executionRegistry;
    private final ExecutionCommandSender executionCommandSender;
    private final WorkflowTaskRepository taskRepository;
    private final TaskAttemptRepository attemptRepository;
    private final AgentRepository agentRepository;
    private final AgentHostInstanceRepository hostInstanceRepository;
    private final ExecutionInputAssembler executionInputAssembler;
    private final WorkflowRepository workflowRepository;
    public TaskDispatchContinuation(
            PendingTaskDispatches pendingTaskDispatches,
            @Lazy HostControlWebSocketHandler hostControlWebSocketHandler,
            SessionRepository sessionRepository,
            SessionContextAssembler sessionContextAssembler,
            ExecutionRegistry executionRegistry,
            ExecutionCommandSender executionCommandSender,
            WorkflowTaskRepository taskRepository,
            TaskAttemptRepository attemptRepository,
            AgentRepository agentRepository,
            AgentHostInstanceRepository hostInstanceRepository,
            ExecutionInputAssembler executionInputAssembler,
            WorkflowRepository workflowRepository) {
        this.pendingTaskDispatches = pendingTaskDispatches;
        this.hostControlWebSocketHandler = hostControlWebSocketHandler;
        this.sessionRepository = sessionRepository;
        this.sessionContextAssembler = sessionContextAssembler;
        this.executionRegistry = executionRegistry;
        this.executionCommandSender = executionCommandSender;
        this.taskRepository = taskRepository;
        this.attemptRepository = attemptRepository;
        this.agentRepository = agentRepository;
        this.hostInstanceRepository = hostInstanceRepository;
        this.executionInputAssembler = executionInputAssembler;
        this.workflowRepository = workflowRepository;
    }

    /**
     * &sect;7.3: the host committed its slot &mdash; ship the assembled
     * {@code session.open}. The parked dispatch is kept: {@code session.opened}
     * consumes it.
     */
    public void onSessionAccepted(UUID sessionId) {
        TaskDispatchContext context = pendingTaskDispatches.peek(sessionId);
        if (context == null) {
            return;
        }
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        SessionOpenPayload open = sessionContextAssembler.assembleContext(
                sessionId, session.getServiceType(), session.getRefId(),
                session.getProjectId(), context.agentProfileId());
        if (context.orchestration()) {
            open = open.withAssignment(context.assignment(), context.assignmentDigest());
        }
        hostControlWebSocketHandler.sendSessionOpen(sessionId, open);
        log.info("Continued staged workflow dispatch for session {} (task {}, orchestration {}) "
                + "— session.open sent", sessionId, context.taskId(), context.orchestration());
    }

    /**
     * &sect;7.4/&sect;8.1: the session is ACTIVE &mdash; mint the execution row,
     * ship {@code execution.start}, and bind the attempt to the serving worker.
     */
    @Transactional
    public void onSessionOpened(UUID sessionId) {
        TaskDispatchContext context = pendingTaskDispatches.take(sessionId)
                .map(PendingTaskDispatches.PendingDispatch::context)
                .orElse(null);
        if (context == null) {
            return;
        }
        Session session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("Staged workflow dispatch for session {} has no session row — dropping", sessionId);
            return;
        }

        Instant deadline = Instant.now().plusSeconds(context.timeoutSeconds());
        Map<String, Object> input = context.orchestration()
                ? Map.of()
                : assembleOrdinaryInput(session, context);
        // The dispatch identity is the orchestration discriminator (§16.2): only
        // an ORCHESTRATOR attempt stamps it, so the terminal bridge can tell an
        // ordinary step's task-attempt sink from the orchestration outcome sink.
        // The execution's requestId keeps the legacy workflow contract — the
        // task id, which is what the task-attempt sink resolves from — while an
        // orchestration execution carries the §16.2 runId.
        SessionExecution execution = executionRegistry.start(
                sessionId,
                context.orchestration()
                        ? context.requestId().toString()
                        : context.taskId().toString(),
                context.orchestration() ? context.attemptId() : null,
                deadline, input).orElse(null);
        if (execution == null) {
            log.warn("execution.start refused for staged workflow session {} (task {})",
                    sessionId, context.taskId());
            abandon(context.attemptId(), "execution.start refused for the serving session");
            return;
        }

        boolean sent = context.orchestration()
                ? executionCommandSender.startOrchestration(execution.getId(), session,
                        new OrchestrationExecutionStartPayload(
                                execution.getId(), sessionId, context.attemptId(),
                                context.attemptId(), context.assignmentDigest(), deadline))
                : executionCommandSender.startExecution(execution.getId(), session,
                        executionStartPayload(sessionId, execution, context, deadline));
        if (!sent) {
            log.warn("Host socket gone for staged workflow session {} — execution.start not delivered",
                    sessionId);
            abandon(context.attemptId(), "Host socket gone before execution.start");
            return;
        }

        bindAttemptToServingWorker(session, context);
        log.info("Shipped staged workflow dispatch for session {} (task {}, execution {}, orchestration {})",
                sessionId, context.taskId(), execution.getId(), context.orchestration());
    }

    /** Drop a parked dispatch whose session can never resume (reject / close). */
    public void onSessionEnded(UUID sessionId) {
        pendingTaskDispatches.discard(sessionId);
    }

    /** The &sect;8.1 ordinary-inference shape: the assembled transcript is the payload. */
    private ExecutionStartPayload executionStartPayload(UUID sessionId, SessionExecution execution,
                                                       TaskDispatchContext context, Instant deadline) {
        Map<String, Object> input = execution.getInputPayload() == null
                ? Map.of() : execution.getInputPayload();
        @SuppressWarnings("unchecked")
        java.util.List<ai.myrmec.engine.websocket.message.payload.InferenceMessage> messages =
                input.get("messages") instanceof java.util.List<?> list
                        ? list.stream()
                            .filter(ai.myrmec.engine.websocket.message.payload.InferenceMessage.class
                                    ::isInstance)
                            .map(ai.myrmec.engine.websocket.message.payload.InferenceMessage.class
                                    ::cast)
                            .toList()
                        : java.util.List.of();
        java.util.List<String> activeTools = java.util.List.of();
        if (input.get("toolPolicy") instanceof Map<?, ?> policy
                && policy.get("activeToolNames") instanceof java.util.List<?> tools) {
            activeTools = tools.stream().map(String::valueOf).toList();
        }
        return new ExecutionStartPayload(
                execution.getId(),
                sessionId,
                context.stepIndex(),
                context.requestId().toString(),
                deadline,
                new ExecutionStartPayload.Input(messages, null, null),
                new ExecutionStartPayload.ToolPolicy(activeTools, "ENGINE"),
                new ExecutionStartPayload.Output(false, context.stepIndex(), "TEXT"));
    }

    /**
     * The &sect;8.1 input block for an ordinary workflow step: the same
     * per-step transcript the legacy {@code inference.assign} shipped (step
     * prompt + task input + resolved knowledge + the pinned profile's system
     * prompt), rewrapped into the execution-lifecycle shape.
     */
    private Map<String, Object> assembleOrdinaryInput(Session session, TaskDispatchContext context) {
        WorkflowTask task = taskRepository.findById(context.taskId()).orElse(null);
        if (task == null) {
            return Map.of();
        }
        return executionInputAssembler.assembleWorkflowInput(
                task, session.getId(), context.stepIndex());
    }
    /**
     * &sect;19.1: the worker serving this session is the Agent row the allocator
     * minted at {@code session.opened} (stamped with the request id and the live
     * instance). The attempt created at dispatch time is bound to it so
     * task/attempt reads and the orchestration outcome path resolve the
     * coordinator row exactly as they did on the legacy path.
     */
    private void bindAttemptToServingWorker(Session session, TaskDispatchContext context) {
        UUID hostedBy = hostIdOf(session);
        Agent worker = hostedBy == null ? null
                : agentRepository.findByAgentHostId(hostedBy).stream()
                        .filter(a -> session.getHostInstanceId() != null
                                && session.getHostInstanceId().equals(a.getAgentHostInstanceId()))
                        .filter(a -> context.requestId().equals(a.getConversationId()))
                        .findFirst()
                        .orElse(null);
        if (worker == null) {
            log.warn("No serving worker row for session {} (request {}) — attempt {} left unbound",
                    session.getId(), context.requestId(), context.attemptId());
            return;
        }
        WorkflowTask task = taskRepository.findById(context.taskId()).orElse(null);
        if (task != null) {
            task.setAgentInstance(worker);
            taskRepository.save(task);
        }
        attemptRepository.findById(context.attemptId()).ifPresent(attempt -> {
            attempt.setAgentInstance(worker);
            attemptRepository.save(attempt);
        });
    }

    /** The durable host of a session's live instance (null when unresolvable). */
    private UUID hostIdOf(Session session) {
        if (session.getHostInstanceId() == null) {
            return null;
        }
        return hostInstanceRepository.findById(session.getHostInstanceId())
                .map(AgentHostInstance::getAgentHostId)
                .orElse(null);
    }

    private void abandon(UUID attemptId, String reason) {
        attemptRepository.findById(attemptId).ifPresent(attempt -> {
            attempt.markAbandoned(reason);
            attemptRepository.save(attempt);
        });
    }
}
