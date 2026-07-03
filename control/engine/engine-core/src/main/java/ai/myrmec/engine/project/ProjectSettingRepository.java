// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link ProjectSetting} rows.
 */
public interface ProjectSettingRepository extends JpaRepository<ProjectSetting, UUID> {

    List<ProjectSetting> findByProjectIdOrderBySettingKeyAsc(UUID projectId);

    Optional<ProjectSetting> findByProjectIdAndSettingKey(UUID projectId, String settingKey);

    boolean existsByProjectIdAndSettingKey(UUID projectId, String settingKey);
}