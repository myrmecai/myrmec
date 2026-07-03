// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link ProjectProviderBinding} rows.
 */
public interface ProjectProviderBindingRepository extends JpaRepository<ProjectProviderBinding, UUID> {

    List<ProjectProviderBinding> findByProjectId(UUID projectId);

    List<ProjectProviderBinding> findByProjectIdAndStatus(UUID projectId, String status);

    Optional<ProjectProviderBinding> findByProjectIdAndProviderId(UUID projectId, UUID providerId);

    boolean existsByProjectIdAndProviderId(UUID projectId, UUID providerId);
}