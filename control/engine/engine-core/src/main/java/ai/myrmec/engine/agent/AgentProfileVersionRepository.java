// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentProfileVersionRepository extends JpaRepository<AgentProfileVersion, UUID> {

    /** The one currently published version of a profile (§16.1). */
    Optional<AgentProfileVersion> findByProfileIdAndStatus(UUID profileId, AgentProfileVersion.Status status);

    /** All versions of a profile, newest first. */
    List<AgentProfileVersion> findByProfileIdOrderByVersionNumberDesc(UUID profileId);

    /** The highest assigned version number (empty profile → empty). */
    @Query("SELECT MAX(v.versionNumber) FROM AgentProfileVersion v WHERE v.profileId = :profileId")
    Optional<Integer> findMaxVersionNumber(@Param("profileId") UUID profileId);

    /** Pessimistic parent-row lock for publish/flip sequences. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM AgentProfile p WHERE p.id = :profileId")
    Optional<AgentProfile> lockProfile(@Param("profileId") UUID profileId);

    /** Draft of a profile, if one exists (only one open draft is allowed). */
    Optional<AgentProfileVersion> findByProfileIdAndStatusOrderById(
            UUID profileId, AgentProfileVersion.Status status);

    /** Versions with their tools fetched (avoids N+1 on detail views). */
    @Query("SELECT DISTINCT v FROM AgentProfileVersion v LEFT JOIN FETCH v.tools WHERE v.profileId = :profileId ORDER BY v.versionNumber DESC")
    List<AgentProfileVersion> findAllByProfileIdWithTools(@Param("profileId") UUID profileId);

    @Query("SELECT DISTINCT v FROM AgentProfileVersion v LEFT JOIN FETCH v.tools WHERE v.id = :id")
    Optional<AgentProfileVersion> findByIdWithTools(@Param("id") UUID id);
}