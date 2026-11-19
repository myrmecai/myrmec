// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.team;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing Team instruction bindings — enables/disables
 * org OPTIONAL instruction assets per Team.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamInstructionBindingService {

    private final TeamInstructionBindingRepository repository;

    @Transactional(readOnly = true)
    public List<TeamInstructionBinding> findAllByTeamId(UUID teamId) {
        return repository.findByTeamId(teamId);
    }

    @Transactional
    public TeamInstructionBinding upsert(UUID teamId, UUID instructionAssetId, boolean enabled) {
        Optional<TeamInstructionBinding> existing =
                repository.findByTeamIdAndInstructionAssetId(teamId, instructionAssetId);

        TeamInstructionBinding binding;
        if (existing.isPresent()) {
            binding = existing.get();
            binding.setEnabled(enabled);
        } else {
            binding = new TeamInstructionBinding();
            binding.setTeamId(teamId);
            binding.setInstructionAssetId(instructionAssetId);
            binding.setEnabled(enabled);
        }

        binding = repository.save(binding);
        log.info("Upserted instruction binding: Team={}, asset={}, enabled={}",
                teamId, instructionAssetId, enabled);
        return binding;
    }
}