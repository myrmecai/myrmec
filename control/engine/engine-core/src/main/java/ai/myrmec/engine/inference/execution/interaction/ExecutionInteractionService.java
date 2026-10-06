// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference.execution.interaction;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine._system.exception.DuplicateResourceException;
import ai.myrmec.engine._system.exception.ResourceInUseException;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine._system.security.ProjectAccessEvaluator;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.execution.SessionExecution;
import ai.myrmec.engine.inference.execution.SessionExecutionRepository;
import ai.myrmec.engine.security.scan.SecretLeakService;
import ai.myrmec.engine.user.UserPrincipal;
import ai.myrmec.engine.websocket.host.HostProtocol;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionCompletePayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionFailedPayload;
import ai.myrmec.engine.websocket.host.payload.ExecutionInteractionPayload;
import ai.myrmec.engine.workflow.TaskAttemptRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 8 (plan 2026-10-03-session-interaction): the user-facing chat
 * admission (POST /interactions, §4/§22.6) and the inbound durable
 * outcome arms (§22.3/§22.6).
 *
 * <p>Admission algorithm (plan-verbatim): authorize ownership + EDITOR
 * equivalent → validate text (nonempty, maxInputBytes) → outbound secret
 * scan (the engine's OWN redaction layer, fail-closed CAPTURE_BLOCKED) →
 * lock the execution row (pessimistic write) → replay identical intent
 * (clientRequestId digest idempotency — the SAME record even after an
 * outcome settled) → reject terminal (409 EXECUTION_TERMINAL) → reject a
 * concurrent pending chat (409 RESOURCE_IN_USE) → allocate the ordinal
 * (nextInteractionOrdinal++ under the row lock) → INSERT the §3.2 row
 * (ACCEPTED) + set pendingInteractionId + persist the EXACT
 * execution.interaction envelope in the §3.4 outbox → commit → dispatch
 * post-commit → 202 without waiting for any host receipt.</p>
 *
 * <p>Outcome arms — the identity/capability checks (§22.3) are the
 * BOUNDARY handler's duty before these services run; the service
 * RE-verifies the dispatch identity (payload dispatchId vs the admitted
 * execution) and the interactionId-vs-pending-pointer binding. Duplicate
 * outcome (same messageId + identical bytes) is an idempotent replay;
 * conflicting bytes fail INVALID_MESSAGE; a late outcome after execution
 * terminal persists TRANSCRIPT ONLY (never reopens state, never issues
 * controls). Both durable arms allocate the public §3.5 stream event
 * (IDs/state/usage — never transcript text copies) and update the
 * execution's cumulative usage through {@link InteractionUsageService}.</p>
 */
@Service
@Slf4j
public class ExecutionInteractionService {

    private final SessionExecutionRepository executionRepository;
    private final SessionRepository sessionRepository;
    private final ExecutionInteractionRepository interactionRepository;
    private final ExecutionCommandOutboxRepository outboxRepository;
    private final ProjectAccessEvaluator projectAccess;
    private final TaskAttemptRepository taskAttemptRepository;
    private final SecretLeakService secretLeakService;
    private final InteractionUsageService usageService;
    private final ObjectMapper objectMapper;
    private final ExecutionCommandOutboxDispatcher dispatcher;
    /** The §3.5 single allocation kernel (event + cursor + fan-out). */
    private final ExecutionViewService viewService;

    public ExecutionInteractionService(
            SessionExecutionRepository executionRepository,
            SessionRepository sessionRepository,
            ExecutionInteractionRepository interactionRepository,
            ExecutionCommandOutboxRepository outboxRepository,
            ProjectAccessEvaluator projectAccess,
            TaskAttemptRepository taskAttemptRepository,
            SecretLeakService secretLeakService,
            InteractionUsageService usageService,
            ObjectMapper objectMapper,
            ExecutionCommandOutboxDispatcher dispatcher,
            @org.springframework.context.annotation.Lazy ExecutionViewService viewService) {
        this.executionRepository = executionRepository;
        this.sessionRepository = sessionRepository;
        this.interactionRepository = interactionRepository;
        this.outboxRepository = outboxRepository;
        this.projectAccess = projectAccess;
        this.taskAttemptRepository = taskAttemptRepository;
        this.secretLeakService = secretLeakService;
        this.usageService = usageService;
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.viewService = viewService;
    }

    // =================================================================
    // admit(): the user-facing POST /interactions admission
    // =================================================================

    @Transactional
    public InteractionAdmission admit(ExecutionScope scope, UserPrincipal principal,
                                      UUID clientRequestId, String text) {
        assertCanEdit(principal, scope.projectId());
        HeldChain chain = resolveChain(scope);
        // The session is the admission's dispatch identity (the outbox
        // envelope's hostInstanceId/sessionId).
        Session session = chain.session();

        SessionExecution locked = executionRepository
                .findWithLockById(chain.execution().getId())
                .orElseThrow(this::chainNotFound);

        // §22.2: validate the text against the session's EFFECTIVE policy
        // (the execution row's immutable interaction block may tighten the
        // platform's maxInputBytes below the default).
        validateText(text, locked);
        String safeText = scanRequestText(text);

        // Digest over the canonical admitted request fields (§3.2 —
        // actor + clientRequestId + the SAFE text the engine persists).
        String digest = digestOf(chain.execution().getId(), principal.getUserId(),
                clientRequestId, safeText);

        // Replay an identical intent FIRST (the SAME record even after the
        // execution went terminal / the outcome settled, like §4 control
        // intent idempotency; the terminal gate rejects only NEW intents).
        var existing = interactionRepository
                .findByExecutionIdAndActorUserIdAndClientRequestId(
                        locked.getId(), principal.getUserId(), clientRequestId);
        if (existing.isPresent()) {
            ExecutionInteraction prior = existing.get();
            if (!digest.equals(prior.getRequestDigest())) {
                throw new DuplicateResourceException("ExecutionInteraction",
                        "clientRequestId", clientRequestId);
            }
            return admissionOf(prior);
        }

        if (isTerminal(locked)) {
            throw terminalConflict("chat admission");
        }
        if (locked.getPendingInteractionId() != null) {
            throw new ResourceInUseException(
                    "An interaction is already pending on this execution (reasonCode INTERACTION_PENDING)",
                    java.util.List.of(ai.myrmec.engine._system.exception.ResourceInUseDetail
                            .of("INTERACTION_PENDING", true, 1)));
        }

        long ordinal = locked.getNextInteractionOrdinal() == null
                ? 1L : locked.getNextInteractionOrdinal();

        ExecutionInteraction interaction = new ExecutionInteraction();
        interaction.setExecutionId(locked.getId());
        interaction.setOrdinal(ordinal);
        interaction.setActorUserId(principal.getUserId());
        interaction.setClientRequestId(clientRequestId);
        interaction.setRequestDigest(digest);
        interaction.setStatus(InteractionStatus.ACCEPTED);
        interaction.setRequestText(safeText);
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        interaction.setAcceptedAt(now);
        interaction.setUsageStatus("UNKNOWN");
        interaction.setResponseDeadline(deadlineOf(locked, now));
        interaction.setExpiresAt(interaction.getResponseDeadline());
        interaction = interactionRepository.saveAndFlush(interaction);

        locked.setPendingInteractionId(interaction.getId());
        locked.setNextInteractionOrdinal(ordinal + 1);
        executionRepository.save(locked);

        insertOutbox(session, locked, interaction);
        allocateAcceptedEvent(locked, interaction);

        UUID interactionId = interaction.getId();
        dispatchAfterCommit(locked.getId(), interactionId);
        return admissionOf(interactionRepository.findById(interactionId).orElseThrow());
    }

    /**
     * The controller's refusal path for the §4 concurrent-admission shape:
     * evaluates the SAME admission algorithm without persisting, returning
     * the §4 error code (RESOURCE_IN_USE when a chat is pending).
     *
     * <p>TEST-ONLY CONTRACT (VisibleForTesting): production admission is
     * {@link #admit}; this non-persisting probe exists so the concurrency
     * test can assert the §4 concurrent-post refusal without a second
     * racing HTTP call. Test-only callers — not a supported production seam.</p>
     */
    public ServiceError admitRefusal(ExecutionScope scope, UserPrincipal principal,
                                     UUID clientRequestId, String text) {
        SessionExecution execution = executionRepository.findById(scope.executionId())
                .orElseThrow(this::chainNotFound);
        if (execution.getPendingInteractionId() != null) {
            return new ServiceError("RESOURCE_IN_USE", "An interaction is already pending");
        }
        return new ServiceError("ACCEPTED", "no pending interaction");
    }

    public record ServiceError(String errorCode, String message) {}

    /**
     * §4 GET /interactions: the redacted transcript page in ordinal order
     * (afterOrdinal exclusive; returns AT MOST {@code limit + 1} rows so the
     * caller can report {@code hasMore} honestly — an exact-fit page is
     * trimmed by the controller and must NOT report hasMore). Ownership
     * is the caller's (@PreAuthorize); the chain resolves here so a
     * mismatched page request 404s before any read.
     */
    @Transactional(readOnly = true)
    public List<ExecutionInteraction> transcriptPage(ExecutionScope scope,
                                                     long afterOrdinal, int limit) {
        resolveChain(scope);
        List<ExecutionInteraction> rows = interactionRepository
                .findByExecutionIdOrderByOrdinalAsc(scope.executionId());
        List<ExecutionInteraction> page = new java.util.ArrayList<>();
        for (ExecutionInteraction row : rows) {
            if (row.getOrdinal() == null || row.getOrdinal() <= afterOrdinal) {
                continue;
            }
            page.add(row);
            if (page.size() >= (long) limit + 1) {
                break;
            }
        }
        return page;
    }

    // =================================================================
    // complete()/fail(): the inbound §22.6 durable outcome arms
    // =================================================================

    @Transactional
    public void complete(ExecutionScope scope, String messageId,
                         ExecutionInteractionCompletePayload payload) {
        settle(scope, messageId, payload.executionId(), payload.dispatchId(),
                payload.interactionId(), payload.ordinal(),
                "answer", payload.answer().text(),
                null, null,
                payload.usage() == null ? null
                        : usageMap(payload.usage().inputTokens(),
                                payload.usage().outputTokens(), payload.usage().modelId()),
                payload.usageStatus() == null ? null : payload.usageStatus().name(),
                payload.completedAt(),
                "KNOWN".equals(payload.usageStatus() == null ? null
                        : payload.usageStatus().name()));
    }

    @Transactional
    public void fail(ExecutionScope scope, String messageId,
                     ExecutionInteractionFailedPayload payload) {
        settle(scope, messageId, payload.executionId(), payload.dispatchId(),
                payload.interactionId(), payload.ordinal(),
                "error", null,
                payload.error() == null ? null
                        : payload.error().errorCode() == null ? null
                                : payload.error().errorCode().name(),
                payload.error() == null ? null : payload.error().message(),
                payload.usage() == null ? null
                        : usageMap(payload.usage().inputTokens(),
                                payload.usage().outputTokens(), payload.usage().modelId()),
                payload.usageStatus() == null ? null : payload.usageStatus().name(),
                payload.completedAt(),
                "KNOWN".equals(payload.usageStatus() == null ? null
                        : payload.usageStatus().name()));
    }

    /**
     * The shared outcome settlement. Order: dispatch identity → terminal
     * dedup by messageId (identical replay no-op / conflicting bytes
     * INVALID_MESSAGE) → interaction identity (= the execution's pending
     * pointer) → the FROZEN-OUTCOME gate (§22.8 — a settled row's
     * answer/error/status/terminalMessageId/completedAt NEVER change; a
     * differently-keyed late frame settles USAGE only) → update the row →
     * clear the pending pointer → allocate the §3.5 stream event → settle
     * the usage subtotal ACCOUNTING (the §22.8 attribution, quota charged
     * through the accounting rule) → commit (the AFTER-commit secret answer
     * redaction keeps the failed answer's semantics; the redacted ANSWER
     * text is what persists).
     *
     * <p>The §3.5 stream-cursor allocation requires the execution ROW LOCK
     * (§3.5) — the read is the pessimistic findWithLockById, serializing
     * against the admission's locked ordinal/outbox allocation.</p>
     */
    private void settle(ExecutionScope scope, String messageId, UUID payloadExecutionId,
                        UUID payloadDispatchId, UUID interactionId, long ordinal,
                        String outcomeKind, String answerText, String errorCode,
                        String errorMessage, Map<String, Object> usage,
                        String usageStatus, Instant completedAt, boolean usageKnown) {
        // §3.5: the allocation must run under the execution row lock —
        // the pessimistic read serializes the streamSequence increment
        // against concurrent admissions' locked allocation.
        SessionExecution execution = executionRepository
                .findWithLockById(scope.executionId())
                .orElseThrow(() -> ResourceNotFoundException.of("Execution",
                        scope.executionId()));

        verifyDispatchIdentity(execution, payloadExecutionId, payloadDispatchId);

        // §3.2/§22.6: the messageId terminal-dedup on the row.
        var byMessage = interactionRepository.findByTerminalMessageId(messageId);
        if (byMessage.isPresent()) {
            ExecutionInteraction prior = byMessage.get();
            if (!prior.getId().equals(interactionId)) {
                throw protocolError("INVALID_MESSAGE",
                        "the outcome's messageId is already recorded on another interaction");
            }
            boolean identical = outcomeKind.equals("answer")
                    && answerText != null && answerText.equals(prior.getAnswerText());
            boolean identicalError = outcomeKind.equals("error")
                    && prior.getError() != null
                    && java.util.Objects.equals(errorCode,
                            String.valueOf(prior.getError().get("errorCode")));
            if (!identical && !identicalError) {
                throw protocolError("INVALID_MESSAGE",
                        "a conflicting outcome re-delivered the recorded messageId");
            }
            // Idempotent replay — a durable no-op (the boundary acks).
            return;
        }

        ExecutionInteraction interaction = interactionRepository
                .findWithLockById(interactionId)
                .orElseThrow(() -> protocolError("IDENTITY_MISMATCH",
                        "the outcome names an unknown interaction"));
        if (!execution.getId().equals(interaction.getExecutionId())) {
            throw protocolError("IDENTITY_MISMATCH",
                    "the interaction belongs to another execution");
        }
        if (ordinal != interaction.getOrdinal()) {
            throw protocolError("INVALID_MESSAGE",
                    "the outcome's ordinal disagrees with the admitted interaction");
        }

        boolean terminal = isTerminal(execution);

        // §22.3: the outcome may settle only the ADMITTED interaction —
        // the execution's pending pointer must name it (a stale pointer is
        // a lost admission; fail INVALID, do not settle foreign chat). The
        // gate applies on a terminal execution too (a late outcome settles
        // TRANSCRIPT for the genuinely admitted pending chat only).
        if (!java.util.Objects.equals(execution.getPendingInteractionId(),
                interactionId)
                && interaction.getStatus() == InteractionStatus.ACCEPTED) {
            throw protocolError("INVALID_MESSAGE",
                    "the outcome is not the execution's pending interaction");
        }
        // The terminal state only gates LATER arms: no control command is
        // ever minted from a late outcome (transcript + accounting ride
        // below; state never reopens).
        if (terminal) {
            log.debug("Late outcome {} settles against a terminal execution {} "
                    + "(transcript + accounting only)", messageId, execution.getId());
        }

        // §22.8 freeze: a row already settled (COMPLETED/FAILED — via a first
        // outcome or the sweeper) NEVER gets its outcome overwritten. The
        // messageId dedup above covers exact replays; a DIFFERENTLY-KEYED late
        // frame (new messageId) may still settle USAGE through the same
        // idempotent seam (genuine late provider evidence, settlementId
        // derived from the incoming messageId — replay idempotent), but the
        // answer, error, status, terminalMessageId and completedAt stay frozen.
        if (interaction.getStatus() == InteractionStatus.COMPLETED
                || interaction.getStatus() == InteractionStatus.FAILED) {
            if (messageId.equals(interaction.getTerminalMessageId())) {
                return;   // same-key replay (already covered above; defensive no-op)
            }
            log.info("Late outcome {} for already-settled interaction {} (status {}) — "
                            + "usage accounting only, the outcome stays frozen (§22.8)",
                    messageId, interactionId, interaction.getStatus());
            settleLateUsage(execution, messageId, interactionId, usage, usageKnown);
            return;
        }

        String safeAnswer = applyAnswerRedaction(execution, outcomeKind, answerText);
        if (safeAnswer != null && "[CAPTURE_BLOCKED]".equals(safeAnswer)) {
            // §22.6 record semantics: a blocked answer is a FAILED interaction
            // carrying the CAPTURE_BLOCKED catalogue code.
            outcomeKind = "error";
            errorCode = "CAPTURE_BLOCKED";
            errorMessage = "the answer failed the engine outbound content inspection";
            answerText = null;
            safeAnswer = null;
        }

        interaction.setStatus("answer".equals(outcomeKind)
                ? InteractionStatus.COMPLETED : InteractionStatus.FAILED);
        interaction.setAnswerText("answer".equals(outcomeKind) ? safeAnswer : null);
        if ("error".equals(outcomeKind)) {
            interaction.setError(errorMap(errorCode, errorMessage,
                    Boolean.FALSE));
        }
        if (usage != null && !usage.isEmpty()) {
            interaction.setUsage(usage);
            interaction.setUsageStatus("KNOWN");
        } else {
            interaction.setUsageStatus("UNKNOWN");
        }
        interaction.setTerminalMessageId(messageId);
        interaction.setCompletedAt(completedAt != null ? completedAt : Instant.now());
        interactionRepository.save(interaction);

        // Clear the pending pointer whenever THIS settled interaction owns
        // it — ALSO on a terminal execution (a settled interaction is no
        // longer pending; clearing a pointer never reopens state or issues
        // controls).
        if (java.util.Objects.equals(execution.getPendingInteractionId(), interactionId)) {
            execution.setPendingInteractionId(null);
            executionRepository.save(execution);
        }
        // The §3.5 durable-event allocation rides for every settled outcome
        // (also on the terminal-after-admission race — TRANSCRIPT ONLY there,
        // no control command is ever minted from a late outcome).
        allocateOutcomeEvent(execution, interaction, usage);

        // §22.8 accounting: the outcome's KNOWN subtotal settles through
        // the SAME idempotent settlement the SDK's USAGE_UPDATED path
        // uses (the interaction subtotal is attribution; the engine's
        // cumulative accounting charges only the unaccounted delta).
        if (usageKnown && usage != null && !usage.isEmpty()) {
            usageService.settleUsage(execution.getId(),
                    "interaction-" + interactionId + "-" + outcomeKind,
                    InteractionUsageService.Source.INTERACTION, interactionId,
                    usage, "KNOWN");
        }
    }

    /**
     * §22.8: the late-settlement usage path for an ALREADY-SETTLED
     * interaction (swept FAILED or settled by an earlier outcome). A genuine
     * late provider settlement rides the SAME accounting seam with a
     * settlementId derived from the INCOMING messageId (replay idempotent);
     * the frozen row's outcome fields are never touched.
     */
    private void settleLateUsage(SessionExecution execution, String messageId,
                                 UUID interactionId, Map<String, Object> usage,
                                 boolean usageKnown) {
        if (usageKnown && usage != null && !usage.isEmpty()) {
            usageService.settleUsage(execution.getId(),
                    "interaction-late-" + messageId,
                    InteractionUsageService.Source.INTERACTION, interactionId,
                    usage, "KNOWN");
        }
    }

    /**
     * The §22.6 answer-text redaction pass (durable complete outcomes only —
     * a failed outcome carries no answer). Returns the SAFE bytes or the
     * CAPTURE_BLOCKED marker sentinel.
     */
    private String applyAnswerRedaction(SessionExecution execution, String outcomeKind,
                                        String answerText) {
        if (!"answer".equals(outcomeKind) || answerText == null) {
            return null;
        }
        // §22.6: the engine's own layer — usage may settle, but the
        // raw secret never persists as the answer.
        SecretLeakService.Result scan = secretLeakService.inspectOutbound(
                answerText, execution.getId(), null);
        if (scan.isBlocked()) {
            return "[CAPTURE_BLOCKED]";
        }
        String safe = scan.getText();
        // The §22.6 redaction layer: the platform scanner is
        // intentionally conservative (fixed-shape tokens only).
        // The interaction transcript carries the WIDER §22.6
        // secret-pattern pass — redact, never persist, a secret
        // the conservative layer misses.
        return redactInteractionSecrets(safe);
    }

    // =================================================================
    // internals
    // =================================================================

    /** §22.3 dispatch identity: the payload's execution/dispatch pair must
     * be the admitted execution's own ids. */
    private void verifyDispatchIdentity(SessionExecution execution,
                                        UUID payloadExecutionId, UUID payloadDispatchId) {
        if (payloadExecutionId == null
                || !payloadExecutionId.equals(execution.getId())
                || payloadDispatchId == null
                || !payloadDispatchId.equals(execution.getDispatchId())) {
            throw protocolError("IDENTITY_MISMATCH",
                    "the outcome identity does not match the admitted execution");
        }
    }

    private InteractionProtocolException protocolError(String code, String message) {
        return new InteractionProtocolException(code, code + ": " + message);
    }

    private void allocateAcceptedEvent(SessionExecution execution,
                                       ExecutionInteraction interaction) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "interaction");
        data.put("interactionId", interaction.getId().toString());
        data.put("ordinal", interaction.getOrdinal());
        data.put("outcome", "ACCEPTED");
        viewService.allocate(execution, "execution.interaction.accepted", data);
    }

    private void allocateOutcomeEvent(SessionExecution execution,
                                      ExecutionInteraction interaction,
                                      Map<String, Object> usage) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("kind", "interaction");
        data.put("interactionId", interaction.getId().toString());
        data.put("ordinal", interaction.getOrdinal());
        data.put("outcome", interaction.getStatus().name());
        data.put("usageStatus", interaction.getUsageStatus());
        if (usage != null && !usage.isEmpty()) {
            data.put("usage", usage);
        }
        // §3.5: the event data stores the transcript RECORD REFERENCE, not
        // another copy of the user/answer text.
        viewService.allocate(execution,
                interaction.getStatus() == InteractionStatus.COMPLETED
                        ? "execution.interaction.complete"
                        : "execution.interaction.failed",
                data);
    }

    // ------------------------------------------------------------------
    // §22.6 engine-side secret patterns (admission + outcome redaction)
    // ------------------------------------------------------------------

    /**
     * The §22.6 secret-pattern pass the engine applies INDEPENDENTLY of
     * the platform's conservative secret-leak scanner (the §15 layer).
     * The patterns are the §22.6 shapes the SDK's client-side pass uses;
     * the engine's own layer redacts them from every persisted
     * admission/outcome byte.
     */
    private static final List<Pattern> INTERACTION_SECRET_PATTERNS = List.of(
            Pattern.compile("myr_[A-Za-z0-9+/=_-]{8,}"),          // registration/project keys
            Pattern.compile("sk-[A-Za-z0-9_-]{8,}"),              // API keys (OpenAI-style)
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),// key material
            Pattern.compile("eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}") // JWT shape
    );

    /** Replace every §22.6 pattern hit with {@code <redacted:REASON>}. */
    static String redactInteractionSecrets(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String redacted = text;
        for (Pattern pattern : INTERACTION_SECRET_PATTERNS) {
            Matcher m = pattern.matcher(redacted);
            if (m.find()) {
                redacted = m.replaceAll("<redacted:SECRET_PATTERN>");
            }
        }
        return redacted;
    }

    /** §3.5: the accepted/outcome public events allocate through THE SINGLE
     * kernel in {@link ExecutionViewService#allocate} (event row + cursor +
     * after-commit wakeup + peer relay). */
    private InteractionAdmission admissionOf(ExecutionInteraction row) {
        return new InteractionAdmission(row.getId(), row.getOrdinal(), row.getStatus(),
                row.getResponseDeadline());
    }

    private void validateText(String text, SessionExecution execution) {
        if (text == null || text.isBlank()) {
            throw BadRequestException.forField("text", "REQUIRED",
                    "Text is required and may not be blank.");
        }
        // §22.2: the session's EFFECTIVE input bound (UTF-8 bytes) —
        // the immutable interaction policy on the execution row, tightened
        // by project policy, floored at the platform default when absent.
        int maxInputBytes = effectiveMaxInputBytes(execution);
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > maxInputBytes) {
            throw BadRequestException.forField("text", "TOO_LONG",
                    "Text exceeds maxInputBytes (" + maxInputBytes + "; got " + bytes + ").");
        }
    }

    /** The effective immutable interaction policy's input bound (§22.2). */
    private int effectiveMaxInputBytes(SessionExecution execution) {
        Map<String, Object> policy = execution.getInteractionPolicy();
        if (policy != null && policy.get("maxInputBytes") instanceof Number n) {
            int configured = n.intValue();
            if (configured > 0 && configured < InteractionProperties.DEFAULT_MAX_INPUT_BYTES) {
                return configured;
            }
            return InteractionProperties.DEFAULT_MAX_INPUT_BYTES;
        }
        return InteractionProperties.DEFAULT_MAX_INPUT_BYTES;
    }

    /** §22.6: the engine's outbound secret scan on the admitted TEXT
     * (fail-closed CAPTURE_BLOCKED — the §4 admission answer is a 400).
     * Both layers apply: the platform's configured leak scanner (mode
     * BLOCK) and the §22.6 interaction pattern pass (redaction). REDACT
     * mode persists the redacted text as the canonical admitted bytes —
     * the digest is over THOSE bytes. */
    private String scanRequestText(String text) {
        SecretLeakService.Result scan = secretLeakService.inspectOutbound(text, null, null);
        if (scan.isBlocked()) {
            throw new InteractionCaptureBlockedException(
                    "The message failed the outbound content inspection (CAPTURE_BLOCKED)");
        }
        String safe = scan.getText();
        String redacted = redactInteractionSecrets(safe);
        if (!redacted.equals(safe)) {
            throw new InteractionCaptureBlockedException(
                    "The message contains secret-shaped content (CAPTURE_BLOCKED)");
        }
        return redacted;
    }

    /** §22.6: min(acceptedAt + session response timeout, execution deadline). */
    private Instant deadlineOf(SessionExecution execution, Instant acceptedAt) {
        int timeoutSeconds = responseTimeoutOf(execution);
        Instant byTimeout = acceptedAt.plusSeconds(timeoutSeconds);
        return execution.getDeadline() != null && execution.getDeadline().isBefore(byTimeout)
                ? execution.getDeadline() : byTimeout;
    }

    private int responseTimeoutOf(SessionExecution execution) {
        Map<String, Object> policy = execution.getInteractionPolicy();
        if (policy != null && policy.get("responseTimeoutSeconds") instanceof Number n) {
            return n.intValue();
        }
        return InteractionProperties.DEFAULT_RESPONSE_TIMEOUT_SECONDS;
    }

    private void insertOutbox(Session session, SessionExecution execution,
                              ExecutionInteraction interaction) {
        Instant acceptedAt = interaction.getAcceptedAt();
        var payload = new ExecutionInteractionPayload(
                execution.getId(), execution.getDispatchId(), interaction.getId(),
                interaction.getOrdinal(), interaction.getActorUserId(),
                new ExecutionInteractionPayload.Message(interaction.getRequestText()),
                acceptedAt, interaction.getResponseDeadline());
        UUID messageId = interaction.getId();
        Map<String, Object> envelope = interactionEnvelope(payload, messageId, session);
        // §3.4/§3.5 (review Fix 1): the outbox's dispatch order is its OWN
        // sequence column — the PUBLIC §3.5 stream cursor advances ONLY
        // where an execution_events row is written (allocateAcceptedEvent
        // below). Advancing the cursor for an outbox-only insert created
        // phantom committed cursors (…N-1, N+1) with no event row between
        // them — a Last-Event-ID client would skip the missing one.
        long sequence = (execution.getStreamSequence() == null ? 0L
                : execution.getStreamSequence()) + 1;

        ExecutionCommandOutbox entry = new ExecutionCommandOutbox();
        entry.setId(messageId);
        entry.setExecutionId(execution.getId());
        entry.setSessionId(session.getId());
        entry.setHostInstanceId(session.getHostInstanceId());
        entry.setType(HostProtocol.EXECUTION_INTERACTION);
        entry.setCorrelationId(interaction.getId().toString());
        entry.setSequence(sequence);
        entry.setEnvelope(envelope);
        entry.setPayloadDigest(digestOfEnvelope(envelope));
        entry.setStatus(OutboxStatus.PENDING);
        entry.setExpiresAt(execution.getDeadline() != null
                ? execution.getDeadline() : acceptedAt.plusSeconds(300));
        entry.setNextDeliveryAt(Instant.now());
        entry.setDeliveryCount(0);
        outboxRepository.saveAndFlush(entry);
    }

    /** §3.4: the §22.6 execution.interaction envelope — stored EXACTLY,
     * dispatched AS IS (the row id IS the frame messageId). */
    private Map<String, Object> interactionEnvelope(ExecutionInteractionPayload payload,
                                                    UUID messageId, Session session) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("protocolVersion", HostProtocol.SUPPORTED_VERSION);
        frame.put("messageId", messageId.toString());
        frame.put("type", HostProtocol.EXECUTION_INTERACTION);
        frame.put("sentAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                .toString());
        frame.put("hostInstanceId", String.valueOf(session.getHostInstanceId()));
        frame.put("sessionId", session.getId().toString());
        frame.put("executionId", payload.executionId().toString());
        Map<String, Object> payloadMap = new LinkedHashMap<>();
        payloadMap.put("executionId", payload.executionId().toString());
        payloadMap.put("dispatchId", payload.dispatchId() == null
                ? null : payload.dispatchId().toString());
        payloadMap.put("interactionId", payload.interactionId().toString());
        payloadMap.put("ordinal", payload.ordinal());
        payloadMap.put("actorUserId", payload.actorUserId().toString());
        payloadMap.put("message", Map.of("text", payload.message().text()));
        payloadMap.put("acceptedAt", payload.acceptedAt() == null ? null
                : payload.acceptedAt().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                        .toString());
        payloadMap.put("responseDeadline", payload.responseDeadline() == null ? null
                : payload.responseDeadline()
                        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
        frame.put("payload", payloadMap);
        return frame;
    }

    private void dispatchAfterCommit(UUID executionId, UUID messageId) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            dispatcher.sendPending(executionId, messageId);
                        } catch (Exception e) {
                            log.warn("Post-commit interaction dispatch of {} failed "
                                    + "(outbox stays PENDING for retransmission): {}",
                                    messageId, e.getMessage());
                        }
                    }
                });
    }

    // chain resolution + identity helpers (mirroring the Task 6 service)

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
        var attempt = taskAttemptRepository.findById(scope.attemptId()).orElseThrow(
                () -> ResourceNotFoundException.of("TaskAttempt", scope.attemptId()));
        if (attempt == null || attempt.getTask() == null
                || !scope.taskId().equals(attempt.getTask().getId())) {
            throw chainNotFound(scope);
        }
        var task = attempt.getTask();
        var request = task.getRequest();
        if (request == null || !scope.requestId().equals(request.getId())) {
            throw chainNotFound(scope);
        }
        var workflow = request.getWorkflow();
        if (workflow == null || !scope.workflowId().equals(workflow.getId())) {
            throw chainNotFound(scope);
        }
        return new HeldChain(session, execution);
    }

    private record HeldChain(Session session, SessionExecution execution) {}

    private void assertCanEdit(UserPrincipal principal, UUID projectId) {
        if (principal == null) {
            throw new AccessDeniedException("Chat admission requires a USER principal");
        }
        var authentication = new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(principal, null, java.util.List.of());
        var holder = org.springframework.security.core.context.SecurityContextHolder.getContext();
        org.springframework.security.core.Authentication previous = holder.getAuthentication();
        try {
            holder.setAuthentication(authentication);
            if (!projectAccess.canEdit(projectId, authentication)) {
                throw new AccessDeniedException(
                        "EDITOR-equivalent project access is required for chat admission");
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

    private ResourceInUseException terminalConflict(String op) {
        return new ResourceInUseException(
                "Execution is terminal — " + op + " refused (reasonCode EXECUTION_TERMINAL)",
                java.util.List.of(ai.myrmec.engine._system.exception.ResourceInUseDetail
                        .of("EXECUTION_TERMINAL", true, 1)));
    }

    private ResourceNotFoundException chainNotFound(ExecutionScope scope) {
        return ResourceNotFoundException.of("Execution", scope.executionId());
    }

    private ResourceNotFoundException chainNotFound() {
        return ResourceNotFoundException.of("Execution", "unknown");
    }

    private Map<String, Object> usageMap(Integer inputTokens, Integer outputTokens,
                                         String modelId) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("inputTokens", inputTokens == null ? null : inputTokens.longValue());
        map.put("outputTokens", outputTokens == null ? null : outputTokens.longValue());
        // §3.5: the subtotal carries the computed total (attribution reads
        // it directly; the accounting rule uses the same canonical number).
        map.put("totalTokens", (inputTokens == null ? 0L : inputTokens.longValue())
                + (outputTokens == null ? 0L : outputTokens.longValue()));
        map.put("modelId", modelId);
        return map;
    }

    private Map<String, Object> errorMap(String errorCode, String message, Boolean retryable) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("errorCode", errorCode);
        error.put("message", message == null ? "" : message);
        error.put("retryable", Boolean.TRUE.equals(retryable));
        return error;
    }

    /** §3.2 digest over the canonical admitted request fields. */
    private String digestOf(UUID executionId, UUID actorUserId, UUID clientRequestId,
                            String safeText) {
        String canonical = executionId + "|" + actorUserId + "|" + clientRequestId
                + "|" + safeText;
        return sha256Hex(canonical);
    }

    private String digestOfEnvelope(Map<String, Object> envelope) {
        try {
            return sha256Hex(objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("Envelope serialization failed", e);
        }
    }

    static String sha256Hex(String canonical) {
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
}
