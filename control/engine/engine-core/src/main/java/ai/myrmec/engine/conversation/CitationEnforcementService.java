// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation;

import ai.myrmec.engine.workflow.EventType;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for enforcing citation requirements on retrieval-grounded answers.
 * 
 * <p>When an agent calls {@code ctx.retrieve()} during a conversation turn,
 * the resulting assistant message MUST cite at least one source by including
 * a chunk_id reference. This service:
 * <ul>
 *   <li>Detects if retrieval was called (checks for RETRIEVAL events)</li>
 *   <li>Extracts retrieval chunk IDs from event metadata</li>
 *   <li>Scans assistant message for citation markers (e.g., [^chunk-id])</li>
 *   <li>Reports violations (retrieval without citations)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CitationEnforcementService {

    /**
     * Pattern for chunk ID references in citations.
     * Matches: [^chunk-<uuid>], [^chunk_<uuid>], [chunk-<uuid>], etc.
     * Also matches more flexible formats like chunk-abc123 for testing.
     * 
     * <p>Standard format from SDK is: chunk_<uuid> (UUID format)</p>
     */
    private static final Pattern CITATION_PATTERN = Pattern.compile(
            "\\[\\^chunk[_-]([a-zA-Z0-9\\-]{8,})\\]|chunk[_-]([a-zA-Z0-9\\-]{8,})",
            Pattern.CASE_INSENSITIVE
    );

    private final ExecutionEventRepository executionEventRepository;

    /**
     * Enforce citation requirements on a message.
     * 
     * @param attemptId the task attempt ID
     * @param message the assistant message content
     * @return CitationEnforcementResult indicating whether requirement was met
     */
    public CitationEnforcementResult enforceRetrievalCitations(UUID attemptId, String message) {
        if (message == null || message.trim().isEmpty()) {
            return CitationEnforcementResult.builder()
                    .retrievalOccurred(false)
                    .retrievalEventCount(0)
                    .retrievalChunkCount(0)
                    .citationsFound(false)
                    .foundChunkIds(Collections.emptyList())
                    .enforcementEnabled(true)
                    .build();
        }

        // Find all RETRIEVAL events for this attempt
        List<ExecutionEvent> retrievalEvents = executionEventRepository.findByAttemptIdAndEventType(
                attemptId,
                EventType.RETRIEVAL
        );

        if (retrievalEvents.isEmpty()) {
            // No retrieval occurred; citations not required
            return CitationEnforcementResult.builder()
                    .retrievalOccurred(false)
                    .retrievalEventCount(0)
                    .retrievalChunkCount(0)
                    .citationsFound(false)
                    .foundChunkIds(Collections.emptyList())
                    .enforcementEnabled(true)
                    .build();
        }

        // Collect all chunk IDs from retrieval events
        Set<String> expectedChunkIds = new HashSet<>();
        int totalChunkCount = 0;
        for (ExecutionEvent event : retrievalEvents) {
            Map<String, Object> data = event.getData();
            if (data != null) {
                @SuppressWarnings("unchecked")
                List<String> chunkIds = (List<String>) data.get("chunkIds");
                if (chunkIds != null) {
                    expectedChunkIds.addAll(chunkIds);
                    totalChunkCount += chunkIds.size();
                }
            }
        }

        // Scan message for citations
        List<String> foundChunkIds = extractChunkIds(message);

        boolean citationsFound = !foundChunkIds.isEmpty();

        log.info("Citation enforcement result: attempt={}, retrievalEvents={}, expectedChunks={}, " +
                        "foundCitations={}, citationCount={}",
                attemptId, retrievalEvents.size(), totalChunkCount, citationsFound, foundChunkIds.size());

        return CitationEnforcementResult.builder()
                .retrievalOccurred(true)
                .retrievalEventCount(retrievalEvents.size())
                .retrievalChunkCount(totalChunkCount)
                .citationsFound(citationsFound)
                .foundChunkIds(foundChunkIds)
                .enforcementEnabled(true)
                .build();
    }

    /**
     * Extract chunk IDs from a message.
     * 
     * @param message the message to scan
     * @return list of chunk IDs found in the message
     */
    private List<String> extractChunkIds(String message) {
        List<String> chunkIds = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(message);
        while (matcher.find()) {
            // Group 1: [^chunk-id] format, Group 2: chunk-id format
            String chunkId = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (chunkId != null && !chunkId.isEmpty()) {
                chunkIds.add(chunkId);
            }
        }
        return chunkIds;
    }
}
