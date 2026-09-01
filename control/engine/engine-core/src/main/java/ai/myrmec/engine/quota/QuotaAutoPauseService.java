// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phase 8c &mdash; quota kill switch (#44). Auto-pauses a scope&apos;s quota when
 * consumption reaches 120% of the limit.
 *
 * <p>Runs in its own {@code REQUIRES_NEW} transaction because the policy
 * engine&apos;s evaluate path is {@code readOnly} — the pause must commit even
 * when the calling evaluation transaction is read-only or later rolls back.
 * The pause is delegated to {@link QuotaService#pause}, which sets
 * {@code paused_at}/{@code paused_by} and emits a {@code QUOTA_PAUSED} audit
 * event. The actor is {@code null} (system-initiated).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaAutoPauseService {

    private final QuotaService quotaService;
    private final QuotaRepository quotaRepository;

    /**
     * Pause the given quota if it is not already paused. Idempotent — the
     * {@code pausedAt == null} guard means a concurrent double-trigger is a
     * harmless no-op; an admin-paused or prior auto-paused quota is untouched.
     *
     * @param quotaId the quota to pause; must exist.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pauseIfNotAlready(UUID quotaId) {
        quotaRepository.findById(quotaId).ifPresent(q -> {
            if (q.getPausedAt() == null) {
                log.warn("Quota {} hit 120% of limit — auto-pausing (kill switch #44)", quotaId);
                // null actor = system-initiated; QuotaService.pause emits QUOTA_PAUSED.
                quotaService.pause(quotaId, null);
            }
        });
    }
}
