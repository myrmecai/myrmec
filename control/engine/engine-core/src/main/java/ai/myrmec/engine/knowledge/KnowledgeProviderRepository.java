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

    List<KnowledgeProvider> findByScopeAndProjectIdIsNull(String scope);

    List<KnowledgeProvider> findByScopeAndProjectId(String scope, UUID projectId);

    boolean existsByScopeAndProjectIdIsNullAndName(String scope, String name);

    boolean existsByScopeAndProjectIdAndName(String scope, UUID projectId, String name);
}