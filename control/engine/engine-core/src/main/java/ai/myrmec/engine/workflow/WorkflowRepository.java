// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.workflow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRepository extends JpaRepository<Workflow, UUID> {

    List<Workflow> findByTeamId(UUID teamId);

    List<Workflow> findByTeamIdAndStatus(UUID teamId, WorkflowStatus status);

    Optional<Workflow> findByTeamIdAndName(UUID teamId, String name);

    boolean existsByTeamIdAndName(UUID teamId, String name);
}
