// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link KnowledgeProvider} rows.
 */
public interface KnowledgeProviderRepository extends JpaRepository<KnowledgeProvider, UUID> {

    List<KnowledgeProvider> findByScopeAndTeamIdIsNull(String scope);

    List<KnowledgeProvider> findByScopeAndTeamId(String scope, UUID teamId);

    boolean existsByScopeAndTeamIdIsNullAndName(String scope, String name);

    boolean existsByScopeAndTeamIdAndName(String scope, UUID teamId, String name);
}