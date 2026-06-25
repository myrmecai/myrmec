package ai.myrmec.engine.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExecutionEvent#retrieval} (feature #32). Verifies the
 * RETRIEVAL audit event captures query + chunk/source IDs + scores (IDs only,
 * never passage text) so an AUDITOR can replay which knowledge fed an answer.
 */
class ExecutionEventRetrievalTest {

    @Test
    void retrievalEventCapturesIdsAndScores() {
        UUID taskId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID kbId = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();
        UUID chunk2 = UUID.randomUUID();
        UUID source1 = UUID.randomUUID();
        UUID source2 = UUID.randomUUID();

        ExecutionEvent event = ExecutionEvent.retrieval(
                taskId, attemptId, kbId, "parental leave policy", 3,
                List.of(chunk1, chunk2),
                List.of(source1, source2),
                List.of(0.91, 0.42));

        assertThat(event.getTaskId()).isEqualTo(taskId);
        assertThat(event.getAttemptId()).isEqualTo(attemptId);
        assertThat(event.getEventType()).isEqualTo(EventType.RETRIEVAL);
        assertThat(event.getSource()).isEqualTo(LogSource.AGENT);
        assertThat(event.getMessage()).isEqualTo("Retrieved 2 chunk(s)");
        assertThat(event.getCreatedAt()).isNotNull();

        assertThat(event.getData())
                .containsEntry("knowledgeBaseId", kbId.toString())
                .containsEntry("query", "parental leave policy")
                .containsEntry("topK", 3)
                .containsEntry("hitCount", 2)
                .containsEntry("chunkIds", List.of(chunk1.toString(), chunk2.toString()))
                .containsEntry("sourceIds", List.of(source1.toString(), source2.toString()))
                .containsEntry("scores", List.of(0.91, 0.42));
        // The passage text is never persisted in the audit row.
        assertThat(event.getData()).doesNotContainKey("passage");
    }

    @Test
    void retrievalEventWithNoHitsRecordsEmptyLists() {
        ExecutionEvent event = ExecutionEvent.retrieval(
                UUID.randomUUID(), null, UUID.randomUUID(), "no matches", 5,
                List.of(), List.of(), List.of());

        assertThat(event.getEventType()).isEqualTo(EventType.RETRIEVAL);
        assertThat(event.getMessage()).isEqualTo("Retrieved 0 chunk(s)");
        assertThat(event.getData())
                .containsEntry("hitCount", 0)
                .containsEntry("chunkIds", List.of())
                .containsEntry("sourceIds", List.of())
                .containsEntry("scores", List.of());
    }

    @Test
    void retrievalEventToleratesNullCollections() {
        ExecutionEvent event = ExecutionEvent.retrieval(
                UUID.randomUUID(), null, UUID.randomUUID(), "q", 1, null, null, null);

        assertThat(event.getMessage()).isEqualTo("Retrieved 0 chunk(s)");
        assertThat(event.getData())
                .containsEntry("hitCount", 0)
                .containsEntry("chunkIds", List.of())
                .containsEntry("sourceIds", List.of())
                .containsEntry("scores", List.of());
    }
}
