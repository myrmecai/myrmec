// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.audit;

import ai.myrmec.engine._system.crypto.HmacService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Hash-chain service for tamper-evident audit logging.
 *
 * <p>Computes {@code event_hash = HMAC_SHA256(key, canonical(prevHash ‖ fields))}
 * on write (per-scope chain), and provides verification that recomputes
 * the chain from {@code audit_events} only — never reads
 * {@code audit_chain_heads} (design decision D8).
 *
 * <p>The HMAC key is a global system secret resolved from the secrets vault
 * via {@link SecretResolverService}. It is auto-created on first use.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditHashChainService {

    private final HmacService hmacService;
    private final AuditChainCanonicalizer canonicalizer;
    private final AuditChainHeadRepository chainHeadRepository;
    private final AuditEventRepository auditEventRepository;
    private final AuditHmacKeyBootstrap hmacKeyBootstrap;

    /** Well-known system secret name for the audit HMAC key. */
    static final String AUDIT_HMAC_KEY_NAME = "audit_hmac_key";

    /**
     * Chain an audit event: compute and set {@code prevEventHash} and
     * {@code eventHash}, and advance the scope's chain head.
     *
     * <p>Must be called within the same transaction as the event insert
     * (the caller's transaction). The head is locked via
     * {@code SELECT … FOR UPDATE} to prevent forks. The hash fields are
     * set on the in-memory entity before the INSERT — they are
     * {@code updatable=false} so they cannot be set via UPDATE later.
     *
     * @param event the audit event to chain (already populated with all
     *              fields, not yet saved to the DB)
     * @return the computed event hash (64-char hex)
     */
    public String chainEvent(AuditEvent event) {
        byte[] hmacKey = resolveHmacKey();

        // Lock the head row for this scope (prevents concurrent fork)
        AuditChainHead head = chainHeadRepository
                .findByScopeTypeAndProjectIdForUpdate(event.getScopeType(), event.getProjectId())
                .orElseGet(() -> createHead(event.getScopeType(), event.getProjectId()));

        // Determine prev hash
        String prevHash = head.isChainingActive() && head.getHeadHash() != null
                ? head.getHeadHash()
                : AuditChainCanonicalizer.GENESIS_PREV_HASH;

        // Compute hash: HMAC(key, canonical(prevHash) ‖ canonical(fields))
        byte[] prevBytes = canonicalizer.canonicalizePrevHash(prevHash);
        byte[] fieldBytes = canonicalizer.canonicalize(event);
        String eventHash = hmacService.hmacSha256Hex(hmacKey, prevBytes, fieldBytes);

        // Set hashes on the event
        event.setPrevEventHash(prevHash);
        event.setEventHash(eventHash);

        // Advance head
        head.setHeadHash(eventHash);
        head.setChainingActive(true);
        head.setUpdatedAt(java.time.Instant.now());
        chainHeadRepository.save(head);

        log.debug("Chained audit event id={} scope={}/{} hash={}",
                event.getId(), event.getScopeType(), event.getProjectId(), eventHash);

        return eventHash;
    }

    /**
     * Verify the integrity of a scope's audit chain by recomputing all
     * hashes from {@code audit_events} only.
     *
     * @param scopeType the scope type ("ORGANIZATION" or "PROJECT")
     * @param projectId the project ID (null for ORG scope)
     * @return verification result
     */
    @Transactional(readOnly = true)
    public VerifyResult verifyScope(String scopeType, UUID projectId) {
        byte[] hmacKey = resolveHmacKey();
        List<AuditEvent> events = auditEventRepository
                .findByScopeTypeAndProjectIdOrderByIdAsc(scopeType, projectId);

        int checkedCount = 0;
        String prevHash = AuditChainCanonicalizer.GENESIS_PREV_HASH;

        for (AuditEvent event : events) {
            // Skip unchained rows (pre-activation / off-period)
            if (event.getEventHash() == null) {
                continue;
            }

            checkedCount++;

            // Verify prev hash links correctly
            if (event.getPrevEventHash() == null
                    || !event.getPrevEventHash().equals(prevHash)) {
                return new VerifyResult(false, checkedCount, event.getId(), "Prev hash mismatch");
            }

            // Recompute hash
            byte[] prevBytes = canonicalizer.canonicalizePrevHash(prevHash);
            byte[] fieldBytes = canonicalizer.canonicalize(event);
            String expectedHash = hmacService.hmacSha256Hex(hmacKey, prevBytes, fieldBytes);

            if (!expectedHash.equals(event.getEventHash())) {
                return new VerifyResult(false, checkedCount, event.getId(), "Hash mismatch");
            }

            prevHash = event.getEventHash();
        }

        return new VerifyResult(true, checkedCount, null, null);
    }

    // --- internals ---

    private byte[] resolveHmacKey() {
        return hmacKeyBootstrap.getHmacKey();
    }

    private AuditChainHead createHead(String scopeType, UUID projectId) {
        AuditChainHead head = new AuditChainHead();
        head.setScopeType(scopeType);
        head.setProjectId(projectId);
        head.setChainingActive(false);
        head.setHeadHash(null);
        return chainHeadRepository.save(head);
    }

    /**
     * Verification result for a scope's chain.
     *
     * @param valid         whether the chain is intact
     * @param checkedCount  number of chained events verified
     * @param firstBrokenId the event ID where the chain broke (null if valid)
     * @param reason        human-readable reason for breakage (null if valid)
     */
    public record VerifyResult(boolean valid, int checkedCount, Long firstBrokenId, String reason) {}
}