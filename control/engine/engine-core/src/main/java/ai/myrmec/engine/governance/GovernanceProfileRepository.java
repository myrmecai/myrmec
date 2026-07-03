// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Persistence for {@link GovernanceProfile} rows.
 */
public interface GovernanceProfileRepository extends JpaRepository<GovernanceProfile, String> {

    List<GovernanceProfile> findAllByOrderByCodeAsc();
}