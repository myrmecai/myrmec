// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link TeamInstructionBinding} rows.
 */
public interface TeamInstructionBindingRepository extends JpaRepository<TeamInstructionBinding, UUID> {

    List<TeamInstructionBinding> findByTeamId(UUID teamId);

    List<TeamInstructionBinding> findByTeamIdAndEnabledTrue(UUID teamId);

    Optional<TeamInstructionBinding> findByTeamIdAndInstructionAssetId(UUID teamId, UUID instructionAssetId);

    boolean existsByTeamIdAndInstructionAssetId(UUID teamId, UUID instructionAssetId);
}