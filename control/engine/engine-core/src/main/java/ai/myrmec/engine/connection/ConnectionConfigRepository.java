// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.connection;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link ConnectionConfig} rows.
 */
public interface ConnectionConfigRepository extends JpaRepository<ConnectionConfig, UUID> {

    List<ConnectionConfig> findByScopeAndTeamIdIsNull(String scope);

    List<ConnectionConfig> findByScopeAndTeamId(String scope, UUID teamId);

    boolean existsByScopeAndTeamIdIsNullAndName(String scope, String name);

    boolean existsByScopeAndTeamIdAndName(String scope, UUID teamId, String name);

    List<ConnectionConfig> findByCredentialSecretId(UUID credentialSecretId);
}