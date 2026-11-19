// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.team;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link TeamSetting} rows.
 */
public interface TeamSettingRepository extends JpaRepository<TeamSetting, UUID> {

    List<TeamSetting> findByTeamIdOrderBySettingKeyAsc(UUID teamId);

    Optional<TeamSetting> findByTeamIdAndSettingKey(UUID teamId, String settingKey);

    boolean existsByTeamIdAndSettingKey(UUID teamId, String settingKey);
}