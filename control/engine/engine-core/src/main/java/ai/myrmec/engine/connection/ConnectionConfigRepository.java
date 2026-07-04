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

    List<ConnectionConfig> findByScopeAndProjectIdIsNull(String scope);

    List<ConnectionConfig> findByScopeAndProjectId(String scope, UUID projectId);

    boolean existsByScopeAndProjectIdIsNullAndName(String scope, String name);

    boolean existsByScopeAndProjectIdAndName(String scope, UUID projectId, String name);

    List<ConnectionConfig> findByCredentialSecretId(UUID credentialSecretId);
}