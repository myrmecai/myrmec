// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine._system.security.ProjectAccessEvaluator;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserRoleRepository;
import ai.myrmec.engine.websocket.host.HostProtocol;
import ai.myrmec.engine.websocket.host.payload.ExecutionCancelPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionControlPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionControlRequestResolvedPayload;
import ai.myrmec.engine.websocket.host.HostProtocolEnvelope;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 6 (plan 2026-10-03-session-interaction): the user-API control
 * service — HOLD/CONTINUE (§22.4) + confirmed CANCEL (§8.8) + §22.7 chat
 * proposals and their decisions.
 *
 * <p>Transactional intent algorithm (plan-verbatim): authorize ownership +
 * actor → lock execution (pessimistic write) → replay identical intent
 * (digest idempotency) → reject terminal/unnegotiated → allocate revision
 * (controlRevision under the row lock) → insert intent + EXACT envelope
 * (ExecutionCommandOutbox, id = messageId) → commit → dispatch the stored
 * envelope post-commit → return 202 WITHOUT waiting for any ack/state.</p>
 *
 * <p>Chat CANCEL (§22.7): NO command before confirm — the proposal row is
 * CONFIRMATION_REQUIRED with an engine-stamped 120s expiry; confirmation
 * creates exactly ONE execution.cancel atomically (row lock + idempotent
 * decide); decline/expiry creates none. Direct and chat paths write the
 * SAME persistent intent/outbox record shapes (origin BUTTON vs CHAT).</p>
 */
@Service
@Slf4j
public class ExecutionControlService {

    /** §22.4: HOLD/CONTINUE only on /controls; CANCEL rides /cancel. */
    private static final String ACTION_HOLD = "HOLD";
    private static final String ACTION_CONTINUE = "CONTINUE";
    private static final String ACTION_CANCEL = "CANCEL";
    private static final String ORIGIN_BUTTON = "BUTTON";
    private static final String ORIGIN_CHAT = "CHAT";
    private static final String REASON_USER_REQUESTED = "USER_REQUESTED";
    /** §8.8/§21.5 — mirrors WorkflowRequestService (existing cancel path). */
    private static final int CANCEL_GRACE_SECONDS = 5;

    private final SessionExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;
    private final ExecutionInteractionRepository interactionRepository;
    private final ExecutionControlRequestRepository controlRequestRepository;
    private final ExecutionCommandOutboxRepository outboxRepository;
    private final ProjectAccessEvaluator projectAccess;
    private final UserRoleRepository userRoleRepository;
    private final ai.myrmec.engine.user.UserRepository userRepository;
    private final ObjectMapper objectMapper;
    /** Post-commit envelope dispatcher (Lazy: no bean-init cycle). */
    private final ExecutionCommandOutboxDispatcher dispatcher;
    /** Ownership-chain lookups (§4: attempt → task → request → workflow). */
    private final ai.myrmec.engine.workflow.TaskAttemptRepository taskAttemptRepository;

    /** §22.7: the engine-stamped cancel-confirmation window. */
    @Value("${myrmec.interaction.confirmation-expiry-seconds:120}")
    private int confirmationExpirySeconds;

    public ExecutionControlService(
            SessionExecutionRepository executionRepository,
            SessionRepository sessionRepository,
            ExecutionInteractionRepository interactionRepository,
            ExecutionControlRequestRepository controlRequestRepository,
            ExecutionCommandOutboxRepository outboxRepository,
            ProjectAccessEvaluator projectAccess,
            UserRoleRepository userRoleRepository,
            ai.myrmec.engine.user.UserRepository userRepository,
            ObjectMapper objectMapper,
            ai.myrmec.engine.workflow.TaskAttemptRepository taskAttemptRepository,
            @org.springframework.context.annotation.Lazy ExecutionCommandOutboxDispatcher dispatcher) {
        this.executionRepository = executionRepository;
        this.sessionRepository = sessionRepository;
        this.interactionRepository = interactionRepository;
        this.controlRequestRepository = controlRequestRepository;
        this.outboxRepository = outboxRepository;
        this.projectAccess = projectAccess;
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.taskAttemptRepository = taskAttemptRepository;
        this.dispatcher = dispatcher;
    }

    // =================================================================
    // control(): direct HOLD/CONTINUE (origin BUTTON)
    // =================================================================

    /**
     * §22.4: a direct HOLD/CONTINUE control intent. No confirmation needed;
     * no optimistic HELD; the 202 receipt is the committed intent.
     */
    @Transactional
    public ControlReceipt control(ExecutionScope scope, UserPrincipal principal,
                                  ControlRequest request) {
        assertCanEdit(principal, scope.projectId(), "control");
        HeldChain chain = resolveChain(scope);

        SessionExecution locked = executionRepository.findWithLockById(chain.execution().getId())
                .orElseThrow(() -> chainNotFound(scope));

        // Replay an identical intent FIRST (§4: identical retries return the
        // SAME record even after the execution went terminal — the terminal
        // gate rejects only NEW intents, plan-verbatim algorithm order).
        String digest = digestOf(directCanonical(request.action(), principal.getUserId(),
                request.clientRequestId()));
        var existing = controlRequestRepository
                .findByExecutionIdAndActorUserIdAndClientRequestId(
                        chain.execution().getId(), principal.getUserId(),
                        request.clientRequestId());
        if (existing.isPresent()) {
            ExecutionControlRequest prior = existing.get();
            if (!digest.equals(prior.getRequestDigest())) {
                throw new DuplicateResourceException("ExecutionControlRequest",
                        "clientRequestId", request.clientRequestId());
            }
            return toReceipt(prior);
        }

        rejectTerminal(locked, "control");

        long revision = locked.getControlRevision() + 1;
        boolean hold = ACTION_HOLD.equals(request.action());
        var payload = new ExecutionControlPayload(
                chain.execution().getId(), chain.execution().getDispatchId(), revision,
                hold ? ExecutionControlPayload.Action.HOLD : ExecutionControlPayload.Action.CONTINUE,
                REASON_USER_REQUESTED,
                hold ? holdPolicyOf(locked) : null,
                null);

        UUID messageId = UUID.randomUUID();
        Map<String, Object> envelope = controlEnvelope(payload, messageId, chain.session());

        ExecutionControlRequest intent = new ExecutionControlRequest();
        intent.setExecutionId(chain.execution().getId());
        intent.setActorUserId(principal.getUserId());
        intent.setAction(request.action());
        intent.setOrigin(ORIGIN_BUTTON);
        intent.setClientRequestId(request.clientRequestId());
        intent.setRequestDigest(digest);
        intent.setStatus(InteractionControlStatus.ACCEPTED);
        intent.setResolutionRevision(revision);
        intent.setControlRevision(revision);
        intent.setCommandMessageId(messageId.toString());
        controlRequestRepository.saveAndFlush(intent);

        insertOutbox(chain, chain.execution(), messageId, HostProtocol.EXECUTION_CONTROL,
                null, envelope);

        locked.setControlRevision(revision);
        executionRepository.save(locked);

        // Post-commit dispatch of the STORED envelope (never rebuilt) — the
        // 202 returns on commit, never on the host ack (§22.7).
        dispatchAfterCommit(chain.execution().getId(), messageId);

        return toReceipt(intent);
    }

    // =================================================================
    // cancel(): the confirmed direct cancel (existing cancel path, §8.8)
    // =================================================================

    /**
     * §22.7: the explicit user CANCEL (the direct button). Uses the EXISTING
     * execution-cancel mechanics — the per-execution relay of an
     * {@code execution.cancel} command with the real dispatchId + grace —
     * NOT workflow-wide cancellation; siblings untouched.
     */
    @Transactional
    public ControlReceipt cancel(ExecutionScope scope, UserPrincipal principal,
                                  ConfirmedCancelRequest request) {
        if (!request.confirmed()) {
            throw BadRequestException.forField("confirmed", "INVALID_VALUE",
                    "Cancel requires explicit confirmation (confirmed:true).");
        }
        assertCanEdit(principal, scope.projectId(), "cancel");
        HeldChain chain = resolveChain(scope);

        SessionExecution locked = executionRepository.findWithLockById(chain.execution().getId())
                .orElseThrow(() -> chainNotFound(scope));

        // Identical retry replays the SAME record even after the execution
        // went terminal (§4; the terminal gate rejects only NEW intents).
        String digest = digestOf(directCanonical(ACTION_CANCEL, principal.getUserId(),
                request.clientRequestId()));
        var existing = controlRequestRepository
                .findByExecutionIdAndActorUserIdAndClientRequestId(
                        chain.execution().getId(), principal.getUserId(),
                        request.clientRequestId());
        if (existing.isPresent()) {
            ExecutionControlRequest prior = existing.get();
            if (!digest.equals(prior.getRequestDigest())) {
                throw new DuplicateResourceException("ExecutionControlRequest",
                        "clientRequestId", request.clientRequestId());
            }
            return toReceipt(prior);
        }

        rejectTerminal(locked, "cancel");

        UUID messageId = UUID.randomUUID();
        ExecutionControlRequest intent = new ExecutionControlRequest();
        intent.setExecutionId(chain.execution().getId());
        intent.setActorUserId(principal.getUserId());
        intent.setAction(ACTION_CANCEL);
        intent.setOrigin(ORIGIN_BUTTON);
        intent.setClientRequestId(request.clientRequestId());
        intent.setRequestDigest(digest);
        intent.setStatus(InteractionControlStatus.ACCEPTED);
        intent.setResolutionRevision(locked.getControlRevision() + 1);
        intent.setCommandMessageId(messageId.toString());
        controlRequestRepository.saveAndFlush(intent);

        Map<String, Object> envelope = cancelEnvelope(chain, messageId, REASON_USER_REQUESTED,
                CANCEL_GRACE_SECONDS);
        // A cancel invalidates outstanding proposals (§3.3 terminal/§22.7).
        invalidatePendingProposals(chain.execution().getId(), Instant.now());
        markCancelling(chain.execution().getId());

        insertOutbox(chain, chain.execution(), messageId, HostProtocol.EXECUTION_CANCEL,
                null, envelope);

        dispatchAfterCommit(chain.execution().getId(), messageId);

        return new ControlReceipt(intent.getId(), null, intent.getStatus().name());
    }

    /** Invalidate pending proposals (audit-only — no command, no deadline reset). */
    @Transactional
    public void invalidatePendingProposals(UUID executionId, Instant decidedAt) {
        var pending = controlRequestRepository.findByExecutionIdAndStatusIn(executionId,
                List.of(InteractionControlStatus.PENDING,
                        InteractionControlStatus.CONFIRMATION_REQUIRED));
        for (ExecutionControlRequest row : pending) {
            row.setStatus(InteractionControlStatus.EXPIRED);
            row.setErrorCode(ExecutionControlRequestResolvedPayload.ErrorCode.EXECUTION_TERMINAL.name());
            if (row.getDecidedAt() == null) {
                row.setDecidedAt(decidedAt != null ? decidedAt : Instant.now());
            }
            controlRequestRepository.save(row);
        }
    }

    // =================================================================
    // propose(): the §22.7 chat-proposal path (host → engine)
    // =================================================================

    /**
     * §22.7: the host's durable proposal for an engine-authorized control.
     * The actor is derived from the ADMITTED interaction row — a
     * host-supplied actor is impossible by shape. The engine RE-AUTHORIZES
     * the derived actor from the CURRENT grant rows (not a live JWT — the
     * actor may hold no token at propose time): a revoked actor gets
     * REJECTED/FORBIDDEN and NO command. The §22.7 check runs on propose
     * AND on confirm; the decide path rechecks the deciding principal.
     */
    @Transactional
    public ProposalReceipt propose(ExecutionScope scope, ExecutionControlRequestPayload payload) {
        HeldChain chain = resolveChain(scope);
        SessionExecution locked = executionRepository.findWithLockById(chain.execution().getId())
                .orElseThrow(() -> chainNotFound(scope));

        // §3.3/§22.7: the proposal ID is the row PK — duplicates reuse bytes.
        var byId = controlRequestRepository.findById(payload.controlRequestId());
        if (byId.isPresent()) {
            return toProposalReceipt(byId.get());
        }

        // Minor H: the payload's dispatchId must name THIS execution's
        // attempt (§22.7 "the exact attempt").
        if (payload.dispatchId() == null || !payload.dispatchId().equals(locked.getDispatchId())) {
            return rejectProposal(chain, payload, locked.getControlRevision(),
                    ExecutionControlRequestResolvedPayload.ErrorCode.INVALID_INTERACTION);
        }

        // §22.7 refusal priority: a terminal execution refuses before any
        // interaction/actor consideration (terminal clears the pending
        // pointer, so the pointer check below must not shadow it).
        boolean terminal = isTerminal(locked);
        if (terminal) {
            return rejectProposal(chain, payload, locked.getControlRevision(),
                    ExecutionControlRequestResolvedPayload.ErrorCode.EXECUTION_TERMINAL);
        }

        // Actor derivation: the admitted pending interaction's actor.
        // §22.7 pending-pointer contract: the proposal's authority is the
        // execution's pendingInteractionId (the admission pointer), not
        // merely the row's status vocabulary — RUNNING (Task 8's live
        // interaction state) stays valid, and any OTHER or missing pointer
        // is INVALID_INTERACTION.
        ExecutionInteraction interaction = interactionRepository
                .findById(payload.interactionId()).orElse(null);
        if (interaction == null || !chain.execution().getId().equals(interaction.getExecutionId())
                || !java.util.Objects.equals(locked.getPendingInteractionId(),
                        payload.interactionId())) {
            return rejectProposal(chain, payload, locked.getControlRevision(),
                    ExecutionControlRequestResolvedPayload.ErrorCode.INVALID_INTERACTION);
        }
        UUID actorUserId = interaction.getActorUserId();

        // §22.7 re-authorization: the derived actor's CURRENT EDITOR-
        // equivalent access from the GRANT ROWS (revocation-safe, no JWT).
        if (!actorHasEditorAccess(actorUserId, scope.projectId())) {
            log.info("Proposal {} rejected: actor {} no longer holds EDITOR on project {}",
                    payload.controlRequestId(), actorUserId, scope.projectId());
            return rejectProposal(chain, payload, locked.getControlRevision(),
                    ExecutionControlRequestResolvedPayload.ErrorCode.FORBIDDEN);
        }

        return switch (payload.action()) {
            case CANCEL -> proposeCancel(chain, payload, locked, interaction, actorUserId);
            case HOLD, CONTINUE -> proposeHoldContinue(chain, payload, locked,
                    interaction, actorUserId);
        };
    }

    /**
     * §22.7 row-based re-authorization: resolve the actor's CURRENT grants
     * from {@code user_roles} (NOT JWT claims — the actor may hold no live
     * token), rebuild the scope-prefixed claim list exactly as JWT minting
     * does, and run the SAME evaluator the routes use. This reuses the
     * platform's access model verbatim: project-scoped grants (PROJECT_OWNER
     * implies EDITOR), group-scoped grants cascading down through the
     * project's group and ITS ancestors (the evaluator walks the ancestors),
     * and system-wide grants — with the implicit-role expansion and the
     * PLATFORM_ADMIN isolation from {@link UserRole.Role}.
     */
    private boolean actorHasEditorAccess(UUID actorUserId, UUID projectId) {
        // User-active parity with the live-JWT path (JwtAuthenticationFilter
        // denies deactivated users before the evaluator ever runs): a
        // deactivated user's grants do not authorize, deny on missing too.
        ai.myrmec.engine.user.User actor = userRepository.findById(actorUserId).orElse(null);
        if (actor == null || !actor.getIsActive()) {
            return false;
        }
        List<String> claims = userRoleRepository.findByUserId(actorUserId).stream()
                .map(UserRole::toJwtClaim)
                .toList();
        var actorPrincipal = new UserPrincipal(actorUserId, "", "", claims);
        var authentication = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(actorPrincipal, null, java.util.List.of());
        return projectAccess.canEdit(projectId, authentication);
    }

    private ProposalReceipt proposeHoldContinue(HeldChain chain,
                                                ExecutionControlRequestPayload payload,
                                                SessionExecution locked,
                                                ExecutionInteraction interaction,
                                                UUID actorUserId) {
        long revision = locked.getControlRevision() + 1;
        boolean hold = payload.action() == ExecutionControlRequestPayload.Action.HOLD;
        var commandPayload = new ExecutionControlPayload(
                chain.execution().getId(), chain.execution().getDispatchId(), revision,
                hold ? ExecutionControlPayload.Action.HOLD : ExecutionControlPayload.Action.CONTINUE,
                REASON_USER_REQUESTED,
                hold ? holdPolicyOf(locked) : null,
                payload.controlRequestId());

        UUID messageId = UUID.randomUUID();
        var intent = new ExecutionControlRequest();
        intent.setId(payload.controlRequestId());
        intent.setExecutionId(chain.execution().getId());
        intent.setInteractionId(interaction.getId());
        intent.setActorUserId(actorUserId);
        intent.setAction(payload.action().name());
        intent.setOrigin(ORIGIN_CHAT);
        intent.setRequestDigest(digestOf(proposalCanonical(payload, actorUserId)));
        intent.setStatus(InteractionControlStatus.ACCEPTED);
        intent.setResolutionRevision(revision);
        intent.setControlRevision(revision);
        intent.setCommandMessageId(messageId.toString());
        intent.setExplanation(redact(payload.explanation()));
        controlRequestRepository.saveAndFlush(intent);

        Map<String, Object> envelope = controlEnvelope(commandPayload, messageId, chain.session());
        insertOutbox(chain, chain.execution(), messageId, HostProtocol.EXECUTION_CONTROL,
                payload.controlRequestId().toString(), envelope);

        locked.setControlRevision(revision);
        executionRepository.save(locked);

        dispatchAfterCommit(chain.execution().getId(), messageId);
        return toProposalReceipt(intent);
    }

    private ProposalReceipt proposeCancel(HeldChain chain,
                                          ExecutionControlRequestPayload payload,
                                          SessionExecution locked,
                                          ExecutionInteraction interaction,
                                          UUID actorUserId) {
        Instant expiresAt = Instant.now().plusSeconds(confirmationExpirySeconds);
        UUID messageId = null;   // NO command before confirm (§22.7).
        var intent = new ExecutionControlRequest();
        intent.setId(payload.controlRequestId());
        intent.setExecutionId(chain.execution().getId());
        intent.setInteractionId(interaction.getId());
        intent.setActorUserId(actorUserId);
        intent.setAction(ACTION_CANCEL);
        intent.setOrigin(ORIGIN_CHAT);
        intent.setRequestDigest(digestOf(proposalCanonical(payload, actorUserId)));
        intent.setStatus(InteractionControlStatus.CONFIRMATION_REQUIRED);
        intent.setResolutionRevision(locked.getControlRevision() + 1);
        intent.setConfirmationExpiresAt(expiresAt);
        intent.setExplanation(redact(payload.explanation()));
        controlRequestRepository.saveAndFlush(intent);
        return toProposalReceipt(intent);
    }

    private ProposalReceipt rejectProposal(HeldChain chain,
                                           ExecutionControlRequestPayload payload,
                                           long revision,
                                           ExecutionControlRequestResolvedPayload.ErrorCode errorCode) {
        var intent = new ExecutionControlRequest();
        intent.setId(payload.controlRequestId());
        intent.setExecutionId(chain.execution().getId());
        intent.setInteractionId(payload.interactionId());
        intent.setActorUserId(derivationFallbackActor());
        intent.setAction(payload.action().name());
        intent.setOrigin(ORIGIN_CHAT);
        intent.setRequestDigest(digestOf(proposalCanonical(payload,
                intent.getActorUserId())));
        intent.setStatus(InteractionControlStatus.REJECTED);
        intent.setResolutionRevision(revision == 0 ? 1 : revision);
        intent.setErrorCode(errorCode.name());
        intent.setExplanation(redact(payload.explanation()));
        controlRequestRepository.saveAndFlush(intent);
        return toProposalReceipt(intent);
    }

    /** A rejected proposal has no real actor (none derivable); stamp 0-UUID. */
    private UUID derivationFallbackActor() {
        return new UUID(0L, 0L);
    }

    // =================================================================
    // decide(): CONFIRM/DECLINE on a pending chat-CANCEL proposal
    // =================================================================

    /**
     * §22.7: only the initiating user or an explicitly authorized
     * EDITOR-equivalent may confirm. Rechecks the CURRENT actor (the
     * proposal ID is not a bearer capability); expiry-checked; idempotent;
     * confirm atomically creates ONE {@code execution.cancel} command.
     */
    @Transactional
    public ProposalReceipt decide(ExecutionScope scope, UUID controlRequestId,
                                  UserPrincipal principal, Decision decision) {
        assertCanEdit(principal, scope.projectId(), "decision");

        HeldChain chain = resolveChain(scope);
        SessionExecution locked = executionRepository.findWithLockById(chain.execution().getId())
                .orElseThrow(() -> chainNotFound(scope));

        // Row lock serializes concurrent deciders (§22.7: "simultaneous
        // confirms issue one").
        ExecutionControlRequest row = controlRequestRepository
                .findWithLockById(controlRequestId).orElse(null);
        if (row == null || !chain.execution().getId().equals(row.getExecutionId())) {
            throw ResourceNotFoundException.of("ExecutionControlRequest", controlRequestId);
        }

        // Idempotent replay of a SETTLED disposition reuses stored bytes.
        // The awaiting-confirmation window is CONFIRMATION_REQUIRED (the
        // §22.7 disposition propose stamped); PENDING is not used by the
        // chat path — a terminal disposition (ACCEPTED/DECLINED/EXPIRED/
        // REJECTED) replays as-is.
        InteractionControlStatus status = row.getStatus();
        if (status != InteractionControlStatus.CONFIRMATION_REQUIRED) {
            return toProposalReceipt(row);
        }

        Instant now = Instant.now();
        if (row.getConfirmationExpiresAt() != null
                && row.getConfirmationExpiresAt().isBefore(now)) {
            // The window elapsed: settle the row EXPIRED in THIS
            // transaction (atomic with the decision) and return the
            // settled disposition — the caller (controller / boundary)
            // maps an EXPIRED receipt to the §4 409 reasonCode
            // CONFIRMATION_EXPIRED. Throwing here would doom the
            // transaction and lose the durable disposition (and a
            // REQUIRES_NEW persist would self-deadlock on the row lock
            // this decision already holds).
            row.setStatus(InteractionControlStatus.EXPIRED);
            row.setErrorCode(ExecutionControlRequestResolvedPayload.ErrorCode.CONFIRMATION_EXPIRED
                    .name());
            row.setDecidedAt(now);
            controlRequestRepository.save(row);
            return toProposalReceipt(row);
        }

        boolean confirm = "CONFIRM".equals(decision.decision());
        if (confirm) {
            // §22.7 "rechecks active execution": a CONFIRM landing after the
            // execution went terminal settles the row (no command, no outbox
            // row — a cancel can't act on a finished execution). It is still
            // a DECIDED disposition confirmed by the principal
            // (resolutionRevision stays the proposal's own).
            if (isTerminal(locked)) {
                row.setStatus(InteractionControlStatus.REJECTED);
                row.setErrorCode(ExecutionControlRequestResolvedPayload.ErrorCode
                        .EXECUTION_TERMINAL.name());
                row.setConfirmedBy(principal.getUserId());
                row.setDecidedAt(now);
                controlRequestRepository.save(row);
                return toProposalReceipt(row);
            }
            // Confirm atomically issues ONE execution.cancel command
            // (the SAME existing cancel path/mechanics as the direct button).
            UUID messageId = UUID.randomUUID();
            Map<String, Object> envelope = cancelEnvelope(chain, messageId,
                    REASON_USER_REQUESTED, CANCEL_GRACE_SECONDS);
            row.setStatus(InteractionControlStatus.ACCEPTED);
            row.setConfirmedBy(principal.getUserId());
            row.setCommandMessageId(messageId.toString());
            row.setDecidedAt(now);
            controlRequestRepository.save(row);
            insertOutbox(chain, chain.execution(), messageId, HostProtocol.EXECUTION_CANCEL,
                    row.getId().toString(), envelope);
            invalidatePendingProposals(chain.execution().getId(), now);
            markCancelling(chain.execution().getId());
            dispatchAfterCommit(chain.execution().getId(), messageId);
            return toProposalReceipt(row);
        }
        row.setStatus(InteractionControlStatus.DECLINED);
        row.setConfirmedBy(principal.getUserId());
        row.setDecidedAt(now);
        controlRequestRepository.save(row);
        // Decline arms no NEW engine timer (§22.5 — the SDK owns the idle
        // interval); decline creates NO command.
        return toProposalReceipt(row);
    }

    // =================================================================
    // chain resolution + intent plumbing
    // =================================================================

    /** Validated ownership chain (§4): session/execution + attempt/task/request/workflow/project all line up. */
    private HeldChain resolveChain(ExecutionScope scope) {
        SessionExecution execution = executionRepository.findById(scope.executionId())
                .orElseThrow(() -> ResourceNotFoundException.of("Execution", scope.executionId()));
        Session session = sessionRepository.findById(execution.getSessionId())
                .orElseThrow(() -> ResourceNotFoundException.of("Session",
                        execution.getSessionId()));
        if (!scope.projectId().equals(session.getProjectId())) {
            throw chainNotFound(scope);
        }
        if (!"WORKFLOW".equals(session.getServiceType())
                || !scope.requestId().equals(session.getRefId())) {
            throw chainNotFound(scope);
        }
        // The attempt must exist and belong to the named task+request.
        TaskAttemptRef ref = attemptRef(scope);
        if (!ref.taskIdMatches || !ref.requestIdMatches || !ref.executionMatches(
                execution.getDispatchId())) {
            throw chainNotFound(scope);
        }
        if (!scope.workflowId().equals(ref.workflowId)) {
            throw chainNotFound(scope);
        }
        return new HeldChain(session, execution, ref.workflowId);
    }

    private record HeldChain(Session session, SessionExecution execution, UUID workflowId) {}

    /**
     * Task-6 handler seam: resolve the COMPLETE §4 ownership chain for an
     * execution (its attempt/task/request/workflow/project) so the host arm
     * can call {@link #propose} without re-deriving the chain. Throws 404
     * when any level fails to line up.
     */
    @Transactional(readOnly = true)
    public ExecutionScope scopeForExecution(UUID executionId) {
        SessionExecution execution = executionRepository.findById(executionId)
                .orElseThrow(() -> ResourceNotFoundException.of("Execution", executionId));
        Session session = sessionRepository.findById(execution.getSessionId())
                .orElseThrow(() -> ResourceNotFoundException.of("Session",
                        execution.getSessionId()));
        if (execution.getDispatchId() == null) {
            throw ResourceNotFoundException.of("TaskAttempt", "unresolved dispatch for "
                    + executionId);
        }
        var attempt = taskAttemptRepository.findById(execution.getDispatchId())
                .orElseThrow(() -> ResourceNotFoundException.of("TaskAttempt",
                        execution.getDispatchId()));
        var task = attempt.getTask();
        if (task == null) {
            throw ResourceNotFoundException.of("WorkflowTask", execution.getDispatchId());
        }
        var request = task.getRequest();
        if (request == null) {
            throw ResourceNotFoundException.of("WorkflowRequest", task.getId());
        }
        var workflow = request.getWorkflow();
        if (workflow == null || workflow.getProject() == null) {
            throw ResourceNotFoundException.of("Workflow", request.getId());
        }
        return new ExecutionScope(workflow.getProject().getId(), workflow.getId(),
                request.getId(), task.getId(), attempt.getId(), executionId);
    }

    private record TaskAttemptRef(UUID taskId, UUID requestId, UUID workflowId,
                                  boolean taskIdMatches, boolean requestIdMatches) {
        boolean executionMatches(UUID dispatchId) {
            return dispatchId != null && taskId != null;
        }
    }

    /** Resolve task/attempt/request chain via the task_attempt row keys. */
    private TaskAttemptRef attemptRef(ExecutionScope scope) {
        var attempt = taskAttemptRepository.findById(scope.attemptId())
                .orElseThrow(() -> ResourceNotFoundException.of("TaskAttempt", scope.attemptId()));
        var task = attempt.getTask();
        if (task == null || !scope.taskId().equals(task.getId())) {
            return new TaskAttemptRef(null, null, null, false, false);
        }
        var request = task.getRequest();
        if (request == null || !scope.requestId().equals(request.getId())) {
            return new TaskAttemptRef(task.getId(), null, null, true, false);
        }
        var workflow = request.getWorkflow();
        if (workflow == null || !scope.workflowId().equals(workflow.getId())) {
            return new TaskAttemptRef(task.getId(), request.getId(), null, true, true);
        }
        var project = workflow.getProject();
        if (project == null || !scope.projectId().equals(project.getId())) {
            return new TaskAttemptRef(task.getId(), request.getId(), workflow.getId(), true, true);
        }
        return new TaskAttemptRef(task.getId(), request.getId(), workflow.getId(), true, true);
    }

    private void assertCanEdit(UserPrincipal principal, UUID projectId, String action) {
        if (principal == null) {
            throw new AccessDeniedException("Control actions require a USER principal");
        }
        // ProjectAccessEvaluator consults the CURRENT grant rows at execution
        // time (revocation-safe) and the platform-admin isolation holds
        // (PLATFORM_ADMIN implies no data-access role).
        var authentication = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(principal, null, java.util.List.of());
        var holder = org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.Authentication previous = holder.getAuthentication();
        try {
            holder.setAuthentication(authentication);
            if (!projectAccess.canEdit(projectId, authentication)) {
                log.debug("Control {} denied: actor {} lacks EDITOR on project {}",
                        action, principal.getUserId(), projectId);
                throw new AccessDeniedException(
                        "EDITOR-equivalent project access is required for " + action);
            }
        } finally {
            holder.setAuthentication(previous);
        }
    }

    private boolean isTerminal(SessionExecution execution) {
        return execution.getTerminalMessageId() != null
                || switch (execution.getState()) {
            case COMPLETED, FAILED, PAUSED, CANCELLED, REJECTED -> true;
            default -> false;
        };
    }

    /**
     * §8.8/§11.2: mark the execution CANCELLING before the cancel frame goes
     * out (the real terminal frame — execution.cancelled — records the
     * §11.3.6 terminal through the registry under its own dedup). Under the
     * SAME execution row lock this arm already holds.
     */
    private void markCancelling(UUID executionId) {
        SessionExecution locked = executionRepository.findWithLockById(executionId)
                .orElse(null);
        if (locked != null && locked.getState() == SessionExecution.State.RUNNING) {
            locked.setState(SessionExecution.State.CANCELLING);
            executionRepository.save(locked);
        }
    }

    private void rejectTerminal(SessionExecution locked, String op) {
        if (isTerminal(locked)) {
            // §4: 409 RESOURCE_IN_USE with details.reasonCode EXECUTION_TERMINAL.
            // The detail's resourceType carries the reasonCode so the
            // standard error handler's details block exposes it.
            throw new ai.myrmec.engine._system.exception.ResourceInUseException(
                    "Execution is terminal — " + op + " refused (reasonCode EXECUTION_TERMINAL)",
                    List.of(ai.myrmec.engine._system.exception.ResourceInUseDetail
                            .of("EXECUTION_TERMINAL", true, 1)));
        }
    }

    private ResourceNotFoundException chainNotFound(ExecutionScope scope) {
        return ResourceNotFoundException.of("Execution", scope.executionId());
    }

    private ExecutionControlPayload.HoldPolicy holdPolicyOf(SessionExecution locked) {
        Map<String, Object> policy = locked.getInteractionPolicy();
        int idle = policy != null && policy.get("idleResumeAfterSeconds") instanceof Number n
                ? n.intValue() : InteractionProperties.DEFAULT_IDLE_RESUME_AFTER_SECONDS;
        return new ExecutionControlPayload.HoldPolicy(idle);
    }

    private static ControlReceipt toReceipt(ExecutionControlRequest intent) {
        return new ControlReceipt(intent.getId(), intent.getControlRevision(),
                intent.getStatus().name());
    }

    private static ProposalReceipt toProposalReceipt(ExecutionControlRequest row) {
        return new ProposalReceipt(row.getId(), row.getResolutionRevision(),
                row.getStatus().name(),
                row.getStatus() == InteractionControlStatus.CONFIRMATION_REQUIRED
                        ? row.getConfirmationExpiresAt() : null,
                row.getCommandMessageId(),
                row.getControlRevision(),
                row.getErrorCode());
    }

    // =================================================================
    // Outbox helpers (§3.4)
    // =================================================================

    private void insertOutbox(HeldChain chain, SessionExecution execution, UUID messageId,
                              String type, String correlationId, Map<String, Object> envelope) {
        // §3.4/§3.5 (review Fix 1): the outbox's dispatch order is its OWN
        // sequence column — the PUBLIC §3.5 stream cursor advances ONLY
        // where an execution_events row is written (the view service's
        // allocate/sweeper paths). Advancing the cursor for an outbox-only
        // insert created phantom committed cursors (…N-1, N+1) with no
        // event row between them, skipping a committed sequence for any
        // Last-Event-ID subscriber.
        Long sequence = execution.getStreamSequence() + 1;
        ExecutionCommandOutbox entry = new ExecutionCommandOutbox();
        entry.setId(messageId);
        entry.setExecutionId(execution.getId());
        entry.setSessionId(chain.session().getId());
        entry.setHostInstanceId(chain.session().getHostInstanceId());
        entry.setType(type);
        entry.setCorrelationId(correlationId);
        entry.setSequence(sequence);
        entry.setEnvelope(envelope);
        entry.setPayloadDigest(digestOfEnvelope(envelope));
        entry.setStatus(OutboxStatus.PENDING);
        entry.setExpiresAt(execution.getDeadline() != null
                ? execution.getDeadline() : Instant.now().plusSeconds(300));
        entry.setNextDeliveryAt(Instant.now());
        entry.setDeliveryCount(0);
        outboxRepository.saveAndFlush(entry);
        // NOTE: the stream cursor is deliberately NOT advanced here —
        // outbox rows are the private §3.4 dispatch order; the public
        // cursor moves only with event rows.
    }

    private void dispatchAfterCommit(UUID executionId, UUID messageId) {
        // TransactionSynchronization: dispatch happens AFTER commit (§3.4),
        // retransmitting the EXACT stored envelope with the SAME messageId.
        org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support
                        .TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            dispatcher.sendPending(executionId, messageId);
                        } catch (Exception e) {
                            log.warn("Post-commit dispatch of {} failed (outbox stays "
                                    + "PENDING for retransmission): {}", messageId, e.getMessage());
                        }
                    }
                });
    }

    // =================================================================
    // Envelope builders (§3.4: serialize once, store EXACTLY, dispatch AS IS)
    // =================================================================

    /**
     * The §22.4 execution.control envelope: the full frame (messageId, type,
     * identities, payload) as a JSON-friendly map. Stored EXACTLY; dispatched
     * AS IS.
     */
    private Map<String, Object> controlEnvelope(ExecutionControlPayload payload,
                                                UUID messageId, Session session) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("protocolVersion", HostProtocol.SUPPORTED_VERSION);
        frame.put("messageId", messageId.toString());
        frame.put("type", HostProtocol.EXECUTION_CONTROL);
        frame.put("sentAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .toString());
        frame.put("hostInstanceId", String.valueOf(session.getHostInstanceId()));
        // camelCase sessionId only — the SDK envelope schema
        // (agents/src/protocol/unifiedFrames.ts) has no snake_case member.
        frame.put("sessionId", session.getId().toString());
        frame.put("executionId", payload.executionId().toString());
        frame.put("payload", payloadMap(payload));
        return frame;
    }

    private Map<String, Object> cancelEnvelope(HeldChain chain, UUID messageId,
                                               String reasonCode, int grace) {
        var payload = new ExecutionCancelPayload(chain.execution().getId(),
                chain.execution().getDispatchId(), reasonCode, Instant.now(), grace);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("protocolVersion", HostProtocol.SUPPORTED_VERSION);
        frame.put("messageId", messageId.toString());
        frame.put("type", HostProtocol.EXECUTION_CANCEL);
        frame.put("sentAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .toString());
        frame.put("hostInstanceId", String.valueOf(chain.session().getHostInstanceId()));
        frame.put("sessionId", chain.session().getId().toString());
        frame.put("executionId", chain.execution().getId().toString());
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("executionId", payload.executionId().toString());
        payloadMap.put("dispatchId", payload.dispatchId() == null
                ? null : payload.dispatchId().toString());
        payloadMap.put("reasonCode", payload.reasonCode());
        payloadMap.put("requestedAt", payload.requestedAt().truncatedTo(
                java.time.temporal.ChronoUnit.MILLIS).toString());
        payloadMap.put("gracePeriodSeconds", payload.gracePeriodSeconds());
        frame.put("payload", payloadMap);
        return frame;
    }

    private Map<String, Object> payloadMap(ExecutionControlPayload payload) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("executionId", payload.executionId().toString());
        map.put("dispatchId", payload.dispatchId() == null
                ? null : payload.dispatchId().toString());
        map.put("controlRevision", payload.controlRevision());
        map.put("action", payload.action().name());
        map.put("reasonCode", payload.reasonCode());
        if (payload.holdPolicy() != null) {
            map.put("holdPolicy", Map.of(
                    "idleResumeAfterSeconds", payload.holdPolicy().idleResumeAfterSeconds()));
        } else {
            map.put("holdPolicy", null);
        }
        map.put("controlRequestId", payload.controlRequestId() == null
                ? null : payload.controlRequestId().toString());
        return map;
    }

    private String digestOfEnvelope(Map<String, Object> envelope) {
        try {
            return digestOf(objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("Envelope serialization failed", e);
        }
    }

    private String digestOf(String canonical) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Digest failed", e);
        }
    }

    /** §3.3 canonical direct-request fields (digest idempotency input). */
    private String directCanonical(String action, UUID actor, UUID clientRequestId) {
        return action + "|" + actor + "|" + clientRequestId;
    }

    /** §3.3 canonical proposal fields (duplicate-proposal idempotency input). */
    private String proposalCanonical(ExecutionControlRequestPayload payload, UUID actor) {
        return payload.controlRequestId() + "|" + payload.action() + "|" + actor + "|"
                + payload.interactionId();
    }

    /** §3.3: explanation survives only after redaction (§15). */
    private String redact(String explanation) {
        if (explanation == null || explanation.isBlank()) {
            return null;
        }
        // V1: conservative fixed redaction — store a bounded, low-risk tail.
        String trimmed = explanation.trim();
        return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
    }
}
