// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.agent.dto.AgentProfileCapabilityInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AgentProfileCapabilityRepository
        extends JpaRepository<AgentProfileCapability, AgentProfileCapabilityId> {

    @Query("""
            SELECT new ai.myrmec.engine.agent.dto.AgentProfileCapabilityInfo(
                c.capabilityKey, c.displayName, c.description)
            FROM AgentProfileCapability apc
            JOIN Capability c ON c.capabilityKey = apc.id.capabilityKey
            WHERE apc.id.profileVersionId = :versionId
            ORDER BY c.capabilityKey
            """)
    List<AgentProfileCapabilityInfo> findCapabilitiesByVersionId(@Param("versionId") UUID versionId);

    @Query("""
            SELECT apc.id.capabilityKey
            FROM AgentProfileCapability apc
            WHERE apc.id.profileVersionId = :versionId
            ORDER BY apc.id.capabilityKey
            """)
    List<String> findCapabilityKeysByVersionId(@Param("versionId") UUID versionId);

    @Modifying
    @Query("DELETE FROM AgentProfileCapability apc WHERE apc.id.profileVersionId = :versionId")
    void deleteByVersionId(@Param("versionId") UUID versionId);

    @Query("""
            SELECT COUNT(apc)
            FROM AgentProfileCapability apc
            WHERE apc.id.profileVersionId = :versionId
              AND apc.id.capabilityKey = :capabilityKey
            """)
    long countByVersionIdAndCapabilityKey(
            @Param("versionId") UUID versionId,
            @Param("capabilityKey") String capabilityKey);
}
