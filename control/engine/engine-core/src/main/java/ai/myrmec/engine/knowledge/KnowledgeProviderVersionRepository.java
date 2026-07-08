// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link KnowledgeProviderVersion} rows.
 */
public interface KnowledgeProviderVersionRepository extends JpaRepository<KnowledgeProviderVersion, UUID> {

    Optional<KnowledgeProviderVersion> findByProviderIdAndStatus(UUID providerId, String status);

    List<KnowledgeProviderVersion> findAllByProviderIdOrderByVersionNumberDesc(UUID providerId);
}