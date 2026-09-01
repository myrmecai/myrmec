// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.inference;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Session} rows.
 */
public interface SessionRepository extends JpaRepository<Session, UUID> {

    Optional<Session> findByRefIdAndServiceType(UUID refId, String serviceType);

    List<Session> findByRefId(UUID refId);

    Optional<Session> findByIdAndStatus(UUID id, String status);
}