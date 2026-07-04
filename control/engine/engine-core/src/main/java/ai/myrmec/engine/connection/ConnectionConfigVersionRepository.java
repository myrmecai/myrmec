// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link ConnectionConfigVersion} rows.
 */
public interface ConnectionConfigVersionRepository extends JpaRepository<ConnectionConfigVersion, UUID> {

    Optional<ConnectionConfigVersion> findByConnectionConfigIdAndStatus(UUID connectionConfigId, String status);

    Optional<ConnectionConfigVersion> findByConnectionConfigIdAndStatusOrderByVersionNumberDesc(UUID connectionConfigId, String status);

    List<ConnectionConfigVersion> findAllByConnectionConfigId(UUID connectionConfigId);
}