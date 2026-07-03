// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.project;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectSettingService {

    private final ProjectSettingRepository repository;

    @Transactional(readOnly = true)
    public List<ProjectSetting> findAll(UUID projectId) {
        return repository.findByProjectIdOrderBySettingKeyAsc(projectId);
    }

    @Transactional(readOnly = true)
    public ProjectSetting find(UUID projectId, String key) {
        return repository.findByProjectIdAndSettingKey(projectId, key)
                .orElseThrow(() -> ResourceNotFoundException.of("ProjectSetting", key));
    }

    @Transactional(readOnly = true)
    public String getString(UUID projectId, String key, String defaultValue) {
        return repository.findByProjectIdAndSettingKey(projectId, key)
                .map(ProjectSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public int getInt(UUID projectId, String key, int defaultValue) {
        String raw = getString(projectId, key, null);
        if (raw == null) return defaultValue;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(UUID projectId, String key, boolean defaultValue) {
        String raw = getString(projectId, key, null);
        if (raw == null) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }

    @Transactional
    public ProjectSetting update(UUID projectId, String key, String value, UUID updatedBy) {
        ProjectSetting setting = repository.findByProjectIdAndSettingKey(projectId, key)
                .orElseGet(() -> {
                    ProjectSetting s = new ProjectSetting();
                    s.setProjectId(projectId);
                    s.setSettingKey(key);
                    s.setValueType("STRING");
                    return s;
                });
        setting.setSettingValue(value);
        setting.setUpdatedBy(updatedBy);
        return repository.save(setting);
    }
}