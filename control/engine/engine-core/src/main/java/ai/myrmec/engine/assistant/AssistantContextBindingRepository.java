// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.assistant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link AssistantContextBinding} rows.
 */
public interface AssistantContextBindingRepository extends JpaRepository<AssistantContextBinding, UUID> {

    List<AssistantContextBinding> findByAssistantVersionId(UUID assistantVersionId);

    List<AssistantContextBinding> findByAssistantVersionIdAndBindingType(UUID assistantVersionId, String bindingType);

    Optional<AssistantContextBinding> findByAssistantVersionIdAndBindingTypeAndTargetId(
            UUID assistantVersionId, String bindingType, UUID targetId);
}