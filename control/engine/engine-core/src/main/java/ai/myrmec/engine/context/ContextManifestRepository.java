// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.context;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link ContextManifest} rows.
 */
public interface ContextManifestRepository extends JpaRepository<ContextManifest, UUID> {

    List<ContextManifest> findByConversationIdOrderBySequenceNoAsc(UUID conversationId);

    Optional<ContextManifest> findByConversationIdAndSequenceNo(UUID conversationId, Long sequenceNo);

    Optional<ContextManifest> findByMessageId(UUID messageId);
}