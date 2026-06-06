package ai.myrmec.engine.quota;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 8a &mdash; consumption bucket for one (quota_id, period_start)
 * pair. New row per period so historical roll-ups are a one-SELECT
 * job and the kill-switch percentage math is trivial.
 */
@Entity
@Table(name = "quota_consumption")
@Getter
@Setter
@NoArgsConstructor
public class QuotaConsumption {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "quota_id", nullable = false)
    private UUID quotaId;

    /** Inclusive start of the period bucket (UTC). */
    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "amount_used", nullable = false)
    private long amountUsed;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
