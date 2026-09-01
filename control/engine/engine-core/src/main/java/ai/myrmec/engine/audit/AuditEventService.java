// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.governance.EffectivePolicy;
import ai.myrmec.engine.governance.GovernancePolicyResolver;
import ai.myrmec.engine.governance.GovernanceScope;
import ai.myrmec.engine.governance.ProductFeature;
import ai.myrmec.engine.project.ProjectSetting;
import ai.myrmec.engine.project.ProjectSettingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**

/**
 * Audit Event Service — append-only write, query by entity/time/actor.
 *
 * <p>This is the generic event recorder used by all versioned entity
 * services. The service only supports insert and read — no update or
 * delete, as the table is append-only by design.</p>
 */
@Slf4j
@Service
public class AuditEventService {

    private final AuditEventRepository repository;
    private final AuditHashChainService hashChainService;
    private final GovernancePolicyResolver governancePolicyResolver;
    private final ProjectSettingRepository projectSettingRepository;

    /** Well-known project setting key for audit integrity opt-in. */
    static final String AUDIT_INTEGRITY_SETTING_KEY = "audit_integrity_enabled";

    /**
     * Explicit constructor to break a circular dependency:
     * {@code AuditEventService → GovernancePolicyResolver → SystemSettingService → AuditEventService}.
     * The {@code @Lazy} annotation on {@code GovernancePolicyResolver} defers
     * its creation until first use, breaking the cycle.
     */
    public AuditEventService(
            AuditEventRepository repository,
            AuditHashChainService hashChainService,
            @Lazy GovernancePolicyResolver governancePolicyResolver,
            ProjectSettingRepository projectSettingRepository) {
        this.repository = repository;
        this.hashChainService = hashChainService;
        this.governancePolicyResolver = governancePolicyResolver;
        this.projectSettingRepository = projectSettingRepository;
    }

    /**
     * Record an audit event. This is the primary write method.
     *
     * <p>Runs in the caller's transaction ({@code REQUIRED}) so the audit
     * commit/rollback is tied to the business operation. Lifecycle events
     * (CREATED, PUBLISHED, DISABLED, …) that reference entities created in
     * the same transaction must use this method — a {@code REQUIRES_NEW}
     * propagation would not see the uncommitted rows and hit FK violations.</p>
     *
     * <p>For failure/security events that must survive the caller's rollback
     * (e.g. LOGIN_FAILED), use {@link #recordFailureEvent}.</p>
     *
     * @param entityType    the entity type (e.g., "connection_config", "instruction_asset")
     * @param entityId      the parent row id
     * @param eventType     the event type (e.g., "CREATED", "PUBLISHED", "DISABLED")
     * @param scopeType     the scope type ("ORGANIZATION" or "PROJECT")
     * @param projectId     the project id (null for org-scoped events)
     * @param actorId       the user id who triggered the event
     * @param actorDisplayName the user's display name (denormalized for survival after deletion)
     * @param versionId     the version row id (null for parent-only events)
     * @param reasonCode    optional reason code (e.g., "ADMIN_DISABLED")
     * @param beforeSnapshot changed fields before the event (null if N/A)
     * @param afterSnapshot  changed fields after the event (null if N/A)
     * @param metadata      extra context (null if N/A)
     * @return the persisted audit event
     */
    @Transactional
    public AuditEvent recordEvent(
            String entityType,
            UUID entityId,
            String eventType,
            String scopeType,
            UUID projectId,
            UUID actorId,
            String actorDisplayName,
            UUID versionId,
            String reasonCode,
            Map<String, Object> beforeSnapshot,
            Map<String, Object> afterSnapshot,
            Map<String, Object> metadata) {
        return buildAndSave(entityType, entityId, eventType, scopeType, projectId,
                actorId, actorDisplayName, versionId, reasonCode,
                beforeSnapshot, afterSnapshot, metadata);
    }

    /**
     * Record a failure/security audit event that must survive the caller's
     * rollback.
     *
     * <p>Runs in {@link Propagation#REQUIRES_NEW} so the audit write commits
     * in its own transaction regardless of the caller's outcome. Use this
     * for events like {@code LOGIN_FAILED} that are recorded before an
     * exception is thrown and the caller's transaction is rolled back.</p>
     *
     * <p><b>Contract:</b> callers must <b>not</b> reference entities created
     * within the caller's uncommitted transaction — the new transaction
     * cannot see them and will hit FK violations. Only reference
     * already-committed rows or null.</p>
     *
     * @param parameters identical to {@link #recordEvent}
     * @return the persisted audit event
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEvent recordFailureEvent(
            String entityType,
            UUID entityId,
            String eventType,
            String scopeType,
            UUID projectId,
            UUID actorId,
            String actorDisplayName,
            UUID versionId,
            String reasonCode,
            Map<String, Object> beforeSnapshot,
            Map<String, Object> afterSnapshot,
            Map<String, Object> metadata) {
        return buildAndSave(entityType, entityId, eventType, scopeType, projectId,
                actorId, actorDisplayName, versionId, reasonCode,
                beforeSnapshot, afterSnapshot, metadata);
    }

    /**
     * Shared build-and-save logic for both {@link #recordEvent} and
     * {@link #recordFailureEvent}.
     */
    private AuditEvent buildAndSave(
            String entityType,
            UUID entityId,
            String eventType,
            String scopeType,
            UUID projectId,
            UUID actorId,
            String actorDisplayName,
            UUID versionId,
            String reasonCode,
            Map<String, Object> beforeSnapshot,
            Map<String, Object> afterSnapshot,
            Map<String, Object> metadata) {

        AuditEvent event = new AuditEvent();
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setVersionId(versionId);
        event.setScopeType(scopeType);
        event.setProjectId(projectId);
        event.setActorId(actorId);
        event.setActorDisplayName(actorDisplayName);
        event.setReasonCode(reasonCode);
        event.setBeforeSnapshot(beforeSnapshot);
        event.setAfterSnapshot(afterSnapshot);
        event.setMetadata(metadata);

        log.info("Recording audit event: type={}, entity={}/{}, actor={}",
                eventType, entityType, entityId, actorDisplayName);

        // Set timestamp explicitly (normally set by @PrePersist, but we need
        // it before chainEvent canonicalizes the fields).
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        // Hash-chain the event if chaining is active for this scope.
        // Must happen BEFORE the initial save because hash fields are
        // updatable=false (no UPDATE after INSERT).
        if (isChainingActiveForScope(scopeType, projectId)) {
            hashChainService.chainEvent(event);
        }

        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByEntity(String entityType, UUID entityId) {
        return repository.findByEntityTypeAndEntityIdOrderByTimestampDesc(entityType, entityId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByScope(String scopeType, UUID projectId) {
        return repository.findByScopeTypeAndProjectIdOrderByTimestampDesc(scopeType, projectId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByActor(UUID actorId) {
        return repository.findByActorIdOrderByTimestampDesc(actorId);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> findByEventType(String eventType) {
        return repository.findByEventTypeOrderByTimestampDesc(eventType);
    }

    /**
     * Paginated search with optional filters. All filter params are optional —
     * if none are provided, returns the most recent events.
     */
    @Transactional(readOnly = true)
    public Page<AuditEvent> search(
            String entityType,
            UUID entityId,
            String eventType,
            UUID actorId,
            Instant since,
            Instant until,
            Pageable pageable) {

        Specification<AuditEvent> spec = Specification.where(null);

        if (entityType != null && !entityType.isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("entityType"), entityType));
        }
        if (entityId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("entityId"), entityId));
        }
        if (eventType != null && !eventType.isBlank()) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("eventType"), eventType));
        }
        if (actorId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("actorId"), actorId));
        }
        if (since != null) {
            spec = spec.and((root, query, cb) ->
                    cb.greaterThanOrEqualTo(root.get("timestamp"), since));
        }
        if (until != null) {
            spec = spec.and((root, query, cb) ->
                    cb.lessThanOrEqualTo(root.get("timestamp"), until));
        }

        return repository.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public AuditEvent findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("AuditEvent", id));
    }

    /**
     * Determine whether hash-chaining is active for a given scope.
     *
     * <p>Resolution:
     * <ol>
     *   <li>Read the governance profile's {@code AUDIT_INTEGRITY} ceiling.</li>
     *   <li>STRICT (ON) ⇒ always chain, ignore project setting.</li>
     *   <li>STANDARD/FLEXIBLE (OFF) ⇒ chain only if the project setting
     *       {@code audit_integrity_enabled} is {@code "true"}.
     *       ORG-scope events (projectId=null) follow the ceiling directly.</li>
     * </ol>
     */
    private boolean isChainingActiveForScope(String scopeType, UUID projectId) {
        EffectivePolicy policy = governancePolicyResolver.resolve(
                projectId != null ? GovernanceScope.ofProject(projectId) : GovernanceScope.orgScope());
        String ceiling = policy.single(ProductFeature.AUDIT_INTEGRITY);

        if ("ON".equals(ceiling)) {
            return true; // STRICT forces chaining
        }

        // STANDARD/FLEXIBLE: chain only if project setting is on.
        // ORG-scope events (projectId=null) have no project setting to check.
        if (projectId == null) {
            return false;
        }

        return projectSettingRepository
                .findByProjectIdAndSettingKey(projectId, AUDIT_INTEGRITY_SETTING_KEY)
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
                .orElse(false);
    }
}