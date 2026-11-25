// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AssistantAgentBindingRepository
        extends JpaRepository<AssistantAgentBinding, UUID> {

    List<AssistantAgentBinding> findByAssistantVersionIdOrderByRoleTypeAsc(UUID assistantVersionId);

    List<AssistantAgentBinding> findByAssistantVersionIdAndRoleType(
            UUID assistantVersionId, AssistantAgentBinding.RoleType roleType);

    /** Existence check for idempotent seeding (unique on assistant_version_id + agent_alias). */
    boolean existsByAssistantVersionIdAndAgentAlias(UUID assistantVersionId, String agentAlias);
}
