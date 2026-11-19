// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link InstructionAsset} rows.
 */
public interface InstructionAssetRepository extends JpaRepository<InstructionAsset, UUID> {

    List<InstructionAsset> findByScopeAndTeamIdIsNull(String scope);

    List<InstructionAsset> findByScopeAndTeamId(String scope, UUID teamId);

    boolean existsByScopeAndTeamIdIsNullAndName(String scope, String name);

    boolean existsByScopeAndTeamIdAndName(String scope, UUID teamId, String name);
}