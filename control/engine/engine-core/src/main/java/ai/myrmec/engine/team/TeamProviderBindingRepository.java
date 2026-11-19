// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link TeamProviderBinding} rows.
 */
public interface TeamProviderBindingRepository extends JpaRepository<TeamProviderBinding, UUID> {

    List<TeamProviderBinding> findByTeamId(UUID teamId);

    List<TeamProviderBinding> findByTeamIdAndStatus(UUID teamId, String status);

    Optional<TeamProviderBinding> findByTeamIdAndProviderId(UUID teamId, UUID providerId);

    boolean existsByTeamIdAndProviderId(UUID teamId, UUID providerId);

    List<TeamProviderBinding> findByProviderId(UUID providerId);
}