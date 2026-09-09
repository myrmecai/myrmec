// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code auth_codes} access. Single-use redemption is a portable
 * compare-and-swap: one UPDATE statement, winner = the single caller
 * whose predicate still matches (exactly one row affected). No
 * {@code FOR UPDATE}/{@code SKIP LOCKED}/advisory locks — works the
 * same on PostgreSQL and H2, and any replica can exchange.
 */
@Repository
public interface AuthCodeRepository extends JpaRepository<AuthCode, UUID> {

    /** Load the row backing a code (for post-redeem verification). */
    Optional<AuthCode> findByCodeHash(String codeHash);

    /**
     * Redeem a code: flip {@code used_at} only when the code exists, is
     * unused, and unexpired. Standard SQL — the DB's own row write
     * lock serializes racing exchanges; the loser sees 0 rows.
     *
     * @return 1 when this caller won the code, 0 otherwise
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update AuthCode c
            set c.usedAt = :now
            where c.codeHash = :codeHash
              and c.usedAt is null
              and c.expiresAt > :now
            """)
    int redeem(
            @Param("codeHash") String codeHash,
            @Param("now") Instant now);

    /** Sweeper support: expired (past {@code expiresAt}) rows. */
    long countByExpiresAtBefore(Instant cutoff);

    /** Sweeper deletion — expired rows carry no value once past TTL. */
    @Modifying
    @Query("delete from AuthCode c where c.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * Override {@code expires_at} on a specific row. Used by tests to
     * artificially age codes past their TTL (the column is mapped
     * {@code updatable = false} — bulk JPQL updates bypass that flag,
     * the same trick as {@code ContextManifestRepository.updateCreatedAt}).
     */
    @Modifying
    @Transactional
    @Query("update AuthCode c set c.expiresAt = :ts where c.codeHash = :codeHash")
    void updateExpiresAt(@Param("codeHash") String codeHash, @Param("ts") Instant ts);
}