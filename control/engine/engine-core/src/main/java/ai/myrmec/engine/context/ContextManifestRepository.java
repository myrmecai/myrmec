// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link ContextManifest} rows.
 */
public interface ContextManifestRepository extends JpaRepository<ContextManifest, UUID> {

    List<ContextManifest> findBySessionIdOrderBySequenceNoAsc(UUID sessionId);

    Optional<ContextManifest> findBySessionIdAndSequenceNo(UUID sessionId, Long sequenceNo);

    Optional<ContextManifest> findByMessageId(UUID messageId);

    /**
     * Delete all manifests for a given governance profile code whose
     * {@code createdAt} is before the cutoff. Used by the
     * {@code ContextManifestRetentionSweeper} to enforce per-profile
     * retention windows.
     *
     * @param profileCode the governance profile code (e.g. STRICT, STANDARD, FLEXIBLE)
     * @param cutoff      manifests older than this instant are deleted
     * @return the number of rows deleted
     */
    @Modifying
    @Query("delete from ContextManifest m where m.governanceProfileCode = :code and m.createdAt < :cutoff")
    int deleteByProfileAndCutoff(@Param("code") String profileCode, @Param("cutoff") Instant cutoff);

    /**
     * Count manifests for a given governance profile code whose
     * {@code createdAt} is before the cutoff. Useful for testing the
     * sweeper without relying on delete-then-count races.
     */
    long countByGovernanceProfileCodeAndCreatedAtBefore(String profileCode, Instant cutoff);

    /**
     * Override {@code created_at} on a specific manifest row. Used by
     * tests to artificially age manifests past the retention cutoff
     * (the {@code @PrePersist} on the entity always sets "now").
     */
    @Modifying
    @Query(value = "UPDATE context_manifests SET created_at = :ts WHERE id = :id", nativeQuery = true)
    void updateCreatedAt(@Param("id") UUID id, @Param("ts") Instant ts);
}