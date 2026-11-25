// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.conversation;

import ai.myrmec.engine.workflow.EventType;
import ai.myrmec.engine.workflow.ExecutionEvent;
import ai.myrmec.engine.workflow.ExecutionEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("CitationEnforcementService Tests")
class CitationEnforcementServiceTest {

    @Mock
    private ExecutionEventRepository executionEventRepository;

    private CitationEnforcementService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CitationEnforcementService(executionEventRepository);
    }

    @Test
    @DisplayName("Should pass when no retrieval occurred")
    void testNoRetrievalNoRequirement() {
        UUID attemptId = UUID.randomUUID();
        String message = "This is a regular response without retrieval.";

        when(executionEventRepository.findByAttemptIdAndEventType(attemptId, EventType.RETRIEVAL))
                .thenReturn(Collections.emptyList());

        CitationEnforcementResult result = service.enforceRetrievalCitations(attemptId, message);

        assertFalse(result.isRetrievalOccurred(), "Should not report retrieval occurred");
        assertEquals(0, result.getRetrievalEventCount());
        assertEquals(0, result.getRetrievalChunkCount());
        assertFalse(result.isViolation(), "Should not be a violation");
        assertTrue(result.isCitationsFound() == false || result.getFoundChunkIds().isEmpty());
    }

    @Test
    @DisplayName("Should pass when retrieval occurred and citations found")
    void testRetrievalWithCitationsPass() {
        UUID attemptId = UUID.randomUUID();
        String chunkId1 = "a1b2c3d4-e5f6-47g8-h9i0-j1k2l3m4n5o6";
        String chunkId2 = "b2c3d4e5-f6g7-48h9-i0j1-k2l3m4n5o6p7";

        String message = String.format(
                "Based on our knowledge base [^chunk_%s], I can tell you that [^chunk_%s] shows...",
                chunkId1, chunkId2
        );

        // Create RETRIEVAL event with chunk IDs
        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(EventType.RETRIEVAL);
        event.setAttemptId(attemptId);
        event.setData(Map.of(
                "knowledgeSourceId", "kb-123",
                "query", "test query",
                "chunkIds", List.of(chunkId1, chunkId2),
                "hitCount", 2
        ));

        when(executionEventRepository.findByAttemptIdAndEventType(attemptId, EventType.RETRIEVAL))
                .thenReturn(List.of(event));

        CitationEnforcementResult result = service.enforceRetrievalCitations(attemptId, message);

        assertTrue(result.isRetrievalOccurred(), "Should report retrieval occurred");
        assertEquals(1, result.getRetrievalEventCount());
        assertEquals(2, result.getRetrievalChunkCount());
        assertTrue(result.isCitationsFound(), "Should find citations");
        assertEquals(2, result.getFoundChunkIds().size());
        assertFalse(result.isViolation(), "Should not be a violation");
    }

    @Test
    @DisplayName("Should fail when retrieval occurred but no citations found")
    void testRetrievalWithoutCitationsViolation() {
        UUID attemptId = UUID.randomUUID();
        String chunkId1 = "a1b2c3d4-e5f6-47g8-h9i0-j1k2l3m4n5o6";
        String chunkId2 = "b2c3d4e5-f6g7-48h9-i0j1-k2l3m4n5o6p7";

        String message = "This is an answer without any citations to the retrieval results.";

        // Create RETRIEVAL event with chunk IDs
        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(EventType.RETRIEVAL);
        event.setAttemptId(attemptId);
        event.setData(Map.of(
                "knowledgeSourceId", "kb-123",
                "query", "test query",
                "chunkIds", List.of(chunkId1, chunkId2),
                "hitCount", 2
        ));

        when(executionEventRepository.findByAttemptIdAndEventType(attemptId, EventType.RETRIEVAL))
                .thenReturn(List.of(event));

        CitationEnforcementResult result = service.enforceRetrievalCitations(attemptId, message);

        assertTrue(result.isRetrievalOccurred(), "Should report retrieval occurred");
        assertEquals(1, result.getRetrievalEventCount());
        assertEquals(2, result.getRetrievalChunkCount());
        assertFalse(result.isCitationsFound(), "Should not find citations");
        assertTrue(result.getFoundChunkIds().isEmpty(), "Should have no found chunk IDs");
        assertTrue(result.isViolation(), "Should be a violation");
    }

    @Test
    @DisplayName("Should extract citations in various formats")
    void testCitationFormatVariations() {
        UUID attemptId = UUID.randomUUID();
        String chunkId = "a1b2c3d4-e5f6-47g8-h9i0-j1k2l3m4n5o6";

        // Test multiple citation formats
        String message = String.format(
                "Format 1: [^chunk_%s] Format 2: chunk-%s Format 3: chunk_%s",
                chunkId, chunkId, chunkId
        );

        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(EventType.RETRIEVAL);
        event.setAttemptId(attemptId);
        event.setData(Map.of(
                "chunkIds", List.of(chunkId),
                "hitCount", 1
        ));

        when(executionEventRepository.findByAttemptIdAndEventType(attemptId, EventType.RETRIEVAL))
                .thenReturn(List.of(event));

        CitationEnforcementResult result = service.enforceRetrievalCitations(attemptId, message);

        assertTrue(result.isCitationsFound(), "Should find citations in all formats");
        assertFalse(result.isViolation(), "Should not be a violation");
    }

    @Test
    @DisplayName("Should handle null or empty message")
    void testNullOrEmptyMessage() {
        UUID attemptId = UUID.randomUUID();

        CitationEnforcementResult resultNull = service.enforceRetrievalCitations(attemptId, null);
        assertFalse(resultNull.isRetrievalOccurred());
        assertTrue(resultNull.getFoundChunkIds().isEmpty());

        CitationEnforcementResult resultEmpty = service.enforceRetrievalCitations(attemptId, "");
        assertFalse(resultEmpty.isRetrievalOccurred());
        assertTrue(resultEmpty.getFoundChunkIds().isEmpty());

        CitationEnforcementResult resultWhitespace = service.enforceRetrievalCitations(attemptId, "   ");
        assertFalse(resultWhitespace.isRetrievalOccurred());
        assertTrue(resultWhitespace.getFoundChunkIds().isEmpty());
    }

    @Test
    @DisplayName("Should handle multiple retrieval events")
    void testMultipleRetrievalEvents() {
        UUID attemptId = UUID.randomUUID();
        String chunkId1 = "a1b2c3d4-e5f6-47g8-h9i0-j1k2l3m4n5o6";
        String chunkId2 = "b2c3d4e5-f6g7-48h9-i0j1-k2l3m4n5o6p7";
        String chunkId3 = "c3d4e5f6-g7h8-49i0-j1k2-l3m4n5o6p7q8";

        String message = String.format("Answer [^chunk_%s]", chunkId1);

        // Two separate retrieval events (e.g., agent called retrieve twice)
        ExecutionEvent event1 = new ExecutionEvent();
        event1.setEventType(EventType.RETRIEVAL);
        event1.setAttemptId(attemptId);
        event1.setData(Map.of("chunkIds", List.of(chunkId1, chunkId2)));

        ExecutionEvent event2 = new ExecutionEvent();
        event2.setEventType(EventType.RETRIEVAL);
        event2.setAttemptId(attemptId);
        event2.setData(Map.of("chunkIds", List.of(chunkId3)));

        when(executionEventRepository.findByAttemptIdAndEventType(attemptId, EventType.RETRIEVAL))
                .thenReturn(List.of(event1, event2));

        CitationEnforcementResult result = service.enforceRetrievalCitations(attemptId, message);

        assertTrue(result.isRetrievalOccurred());
        assertEquals(2, result.getRetrievalEventCount());
        assertEquals(3, result.getRetrievalChunkCount(), "Should count all chunks from both events");
        assertTrue(result.isCitationsFound(), "Should find at least one citation");
        assertFalse(result.isViolation(), "Should not be a violation (found at least one citation)");
    }

    @Test
    @DisplayName("Should handle malformed RETRIEVAL event data")
    void testMalformedRetrievalEventData() {
        UUID attemptId = UUID.randomUUID();
        String message = "Any message";

        // RETRIEVAL event with missing chunkIds
        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(EventType.RETRIEVAL);
        event.setAttemptId(attemptId);
        event.setData(Map.of("query", "test query")); // No chunkIds

        when(executionEventRepository.findByAttemptIdAndEventType(attemptId, EventType.RETRIEVAL))
                .thenReturn(List.of(event));

        // Should not throw; should handle gracefully
        CitationEnforcementResult result = service.enforceRetrievalCitations(attemptId, message);

        assertTrue(result.isRetrievalOccurred(), "Should still detect retrieval occurred");
        assertEquals(1, result.getRetrievalEventCount());
        assertEquals(0, result.getRetrievalChunkCount(), "Should handle missing chunkIds");
    }

    @Test
    @DisplayName("Should provide human-readable summary")
    void testSummaryMessages() {
        UUID attemptId = UUID.randomUUID();

        // Case 1: No retrieval
        when(executionEventRepository.findByAttemptIdAndEventType(
                eq(attemptId), any())).thenReturn(Collections.emptyList());
        CitationEnforcementResult result1 = service.enforceRetrievalCitations(attemptId, "message");
        assertTrue(result1.summary().contains("No retrieval occurred"));

        // Case 2: Retrieval with citations
        ExecutionEvent event = new ExecutionEvent();
        event.setEventType(EventType.RETRIEVAL);
        event.setAttemptId(attemptId);
        event.setData(Map.of("chunkIds", List.of("chunk-id")));
        when(executionEventRepository.findByAttemptIdAndEventType(attemptId, EventType.RETRIEVAL))
                .thenReturn(List.of(event));
        CitationEnforcementResult result2 = service.enforceRetrievalCitations(
                attemptId,
                "Answer with [^chunk-id]"
        );
        assertTrue(result2.summary().contains("citations found"));

        // Case 3: Retrieval without citations
        CitationEnforcementResult result3 = service.enforceRetrievalCitations(
                attemptId,
                "Answer without citations"
        );
        assertTrue(result3.summary().contains("CITATION VIOLATION"));
    }
}
