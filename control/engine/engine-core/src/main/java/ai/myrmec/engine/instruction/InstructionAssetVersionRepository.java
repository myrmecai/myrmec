// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.instruction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link InstructionAssetVersion} rows.
 */
public interface InstructionAssetVersionRepository extends JpaRepository<InstructionAssetVersion, UUID> {

    Optional<InstructionAssetVersion> findByAssetIdAndStatus(UUID assetId, String status);
}