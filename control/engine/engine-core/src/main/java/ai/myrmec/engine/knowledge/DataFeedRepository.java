// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Persistence for {@link DataFeed} rows.
 */
public interface DataFeedRepository extends JpaRepository<DataFeed, UUID> {

    List<DataFeed> findByScopeAndTeamIdIsNull(String scope);

    List<DataFeed> findByScopeAndTeamId(String scope, UUID teamId);

    List<DataFeed> findByProviderVersionId(UUID providerVersionId);

    List<DataFeed> findByDatasetName(String datasetName);
}