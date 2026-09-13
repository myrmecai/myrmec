// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounds the append-only instance history (design 2026-09-11 §3.2a / protocol
 * §19.1): CLOSED rows older than the retention window are deleted; OPEN rows
 * are NEVER deleted regardless of age. A crash-looping supervisor must not
 * grow the table without limit. Referencing agents rows keep their
 * agent_host_id and get a nulled agent_host_instance_id (FK SET NULL).
 */
@Service
@Slf4j
public class AgentHostInstanceRetentionSweeper {

    private final AgentHostInstanceRepository instanceRepository;
    private final boolean enabled;
    private final int retentionDays;

    public AgentHostInstanceRetentionSweeper(
            AgentHostInstanceRepository instanceRepository,
            @Value("${myrmec.agent.instance-retention.enabled:true}") boolean enabled,
            @Value("${myrmec.agent.instance-retention.days:30}") int retentionDays) {
        this.instanceRepository = instanceRepository;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
    }

    /** Scheduled entry point — disabled in the e2e profile like the reaper. */
    @Scheduled(fixedDelayString = "${myrmec.agent.instance-retention.interval-ms:3600000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        sweep(Instant.now());
    }

    /** Delete CLOSED instances older than the cutoff; returns rows removed. */
    @Transactional
    public int sweep(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        int deleted = instanceRepository.deleteClosedBefore(cutoff);
        if (deleted > 0) {
            log.info("Instance retention sweep deleted {} CLOSED rows before {}", deleted, cutoff);
        }
        return deleted;
    }
}
