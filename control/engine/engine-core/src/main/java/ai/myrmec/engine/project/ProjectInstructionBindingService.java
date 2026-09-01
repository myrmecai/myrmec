// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing project instruction bindings — enables/disables
 * org OPTIONAL instruction assets per project.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectInstructionBindingService {

    private final ProjectInstructionBindingRepository repository;

    @Transactional(readOnly = true)
    public List<ProjectInstructionBinding> findAllByProjectId(UUID projectId) {
        return repository.findByProjectId(projectId);
    }

    @Transactional
    public ProjectInstructionBinding upsert(UUID projectId, UUID instructionAssetId, boolean enabled) {
        Optional<ProjectInstructionBinding> existing =
                repository.findByProjectIdAndInstructionAssetId(projectId, instructionAssetId);

        ProjectInstructionBinding binding;
        if (existing.isPresent()) {
            binding = existing.get();
            binding.setEnabled(enabled);
        } else {
            binding = new ProjectInstructionBinding();
            binding.setProjectId(projectId);
            binding.setInstructionAssetId(instructionAssetId);
            binding.setEnabled(enabled);
        }

        binding = repository.save(binding);
        log.info("Upserted instruction binding: project={}, asset={}, enabled={}",
                projectId, instructionAssetId, enabled);
        return binding;
    }
}