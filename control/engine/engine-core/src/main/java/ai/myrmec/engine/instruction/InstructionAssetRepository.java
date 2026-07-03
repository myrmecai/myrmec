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

    List<InstructionAsset> findByScopeAndProjectIdIsNull(String scope);

    List<InstructionAsset> findByScopeAndProjectId(String scope, UUID projectId);

    boolean existsByScopeAndProjectIdIsNullAndName(String scope, String name);

    boolean existsByScopeAndProjectIdAndName(String scope, UUID projectId, String name);
}