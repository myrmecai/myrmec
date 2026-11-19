// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link KnowledgeSource} rows.
 */
public interface KnowledgeSourceRepository extends JpaRepository<KnowledgeSource, UUID> {

    List<KnowledgeSource> findByScopeAndTeamIdIsNull(String scope);

    List<KnowledgeSource> findByScopeAndTeamId(String scope, UUID teamId);

    List<KnowledgeSource> findByProviderVersionId(UUID providerVersionId);
}