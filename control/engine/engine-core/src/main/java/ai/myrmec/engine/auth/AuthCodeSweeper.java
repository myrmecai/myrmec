// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Hygiene sweep for expired {@code auth_codes} rows. Correctness never
 * depends on this sweeper — every redemption re-checks the TTL lazily
 * (the CAS predicate) — but expired one-time codes carry no value and
 * the table should not grow without bound. Follows the established
 * sweeper pattern: direct-callable in tests, disabled flag, interval
 * from settings.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuthCodeSweeper {

    private final AuthCodeRepository authCodeRepository;

    /** Disabled by default in tests that don't want timing pressure. */
    @Value("${myrmec.auth.code-sweeper.enabled:true}")
    private boolean enabled;

    /**
     * Purge rows past their TTL. Production default 5 minutes — codes
     * live 60s, so this runs well past any live code's lifetime.
     * <p>
     * Transaction is declared here because {@code @Scheduled} is the proxy
     * entry point; a {@code @Transactional} on the private self-call below
     * would be ignored by Spring AOP.
     */
    @Scheduled(fixedRateString = "${myrmec.auth.code-sweeper.interval-ms:300000}")
    @Transactional
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            int purged = purgeExpired(Instant.now());
            if (purged > 0) {
                log.info("Purged {} expired authorization codes", purged);
            }
        } catch (Exception e) {
            log.warn("Authorization-code sweep failed: {}", e.getMessage(), e);
        }
    }

    /**
     * The actual deletion — separate so tests can call it directly
     * without the {@code @Scheduled} timer.
     */
    @Transactional
    public int purgeExpired(Instant cutoff) {
        return authCodeRepository.deleteByExpiresAtBefore(cutoff);
    }
}