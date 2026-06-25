// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation;

import lombok.Builder;
import lombok.Value;

import java.util.List;

/**
 * Result of citation enforcement validation on an assistant message.
 * 
 * <p>When a conversation turn calls {@code ctx.retrieve()} and receives
 * retrieval results, the assistant's final message MUST contain at least
 * one chunk_id reference (e.g., {@code [^chunk-uuid]} or similar citation
 * marker) to acknowledge the source. This result captures whether that
 * requirement was met.
 */
@Value
@Builder
public class CitationEnforcementResult {
    
    /**
     * True if retrieval was called during this attempt; false otherwise.
     */
    private boolean retrievalOccurred;
    
    /**
     * Number of retrieval events found for this task attempt.
     */
    private int retrievalEventCount;
    
    /**
     * Total chunks available from retrieval results.
     */
    private int retrievalChunkCount;
    
    /**
     * True if the message contains at least one valid chunk_id reference.
     */
    private boolean citationsFound;
    
    /**
     * List of chunk IDs found in the message (if any).
     * Empty list if no citations found.
     */
    private List<String> foundChunkIds;
    
    /**
     * True if citation enforcement is enabled; false if disabled/bypassed.
     * A non-blocking audit-only mode can set this to false to record
     * violations without failing the task.
     */
    private boolean enforcementEnabled;
    
    /**
     * True if this result represents a violation (retrieval without citations).
     */
    public boolean isViolation() {
        return retrievalOccurred && !citationsFound && enforcementEnabled;
    }
    
    /**
     * Human-readable summary of enforcement result.
     */
    public String summary() {
        if (!retrievalOccurred) {
            return "No retrieval occurred; citations not required.";
        }
        if (citationsFound) {
            return String.format("Retrieval called (%d chunks); %d citations found in message.",
                    retrievalChunkCount, foundChunkIds.size());
        }
        return String.format("CITATION VIOLATION: Retrieval called (%d chunks) but no citations found in message.",
                retrievalChunkCount);
    }
}
