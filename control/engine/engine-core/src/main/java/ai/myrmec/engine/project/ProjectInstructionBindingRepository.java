// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link ProjectInstructionBinding} rows.
 */
public interface ProjectInstructionBindingRepository extends JpaRepository<ProjectInstructionBinding, UUID> {

    List<ProjectInstructionBinding> findByProjectId(UUID projectId);

    List<ProjectInstructionBinding> findByProjectIdAndEnabledTrue(UUID projectId);

    Optional<ProjectInstructionBinding> findByProjectIdAndInstructionAssetId(UUID projectId, UUID instructionAssetId);

    boolean existsByProjectIdAndInstructionAssetId(UUID projectId, UUID instructionAssetId);
}