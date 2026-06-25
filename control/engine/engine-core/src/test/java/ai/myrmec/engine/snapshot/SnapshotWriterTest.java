package ai.myrmec.engine.snapshot;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9a — exercises the snapshot writer + repository end-to-end on
 * the same H2 DB the rest of the engine uses, covering:
 * <ul>
 *   <li>happy-path persistence with SHA256 + size accounting,</li>
 *   <li>byte-boundary truncation past the 256KB cap,</li>
 *   <li>sampling-rate=0 dropping the row without raising.</li>
 * </ul>
 */
class SnapshotWriterTest extends IntegrationTestBase {

    @Autowired private SnapshotWriter snapshotWriter;
    @Autowired private ExecutionSnapshotRepository snapshotRepository;
    @Autowired private TestDataBuilder data;
    @Autowired private ProjectRepository projectRepository;

    @Test
    void persistsInlineRowWithShaAndSize() {
        Project project = data.project().named("snapshot-inline").create();

        Map<String, Object> payload = new HashMap<>();
        payload.put("hello", "world");
        payload.put("answer", 42);

        Optional<ExecutionSnapshot> written = snapshotWriter.write(
                SnapshotWriter.SnapshotRequest.builder()
                        .projectId(project.getId())
                        .eventType("UNIT_TEST_EVENT")
                        .payload(payload)
                        .build());

        assertThat(written).isPresent();
        ExecutionSnapshot row = written.get();
        assertThat(row.getId()).isNotNull();
        assertThat(row.getProjectId()).isEqualTo(project.getId());
        assertThat(row.getEventType()).isEqualTo("UNIT_TEST_EVENT");
        assertThat(row.getPayloadJson()).contains("\"hello\":\"world\"");
        assertThat(row.getPayloadSha256()).hasSize(64).matches("[0-9a-f]+");
        assertThat(row.getSizeBytes()).isPositive();
        assertThat(row.isTruncated()).isFalse();
        assertThat(row.isSampled()).isTrue();
        assertThat(row.getCreatedAt()).isNotNull();

        List<ExecutionSnapshot> stored = snapshotRepository
                .findByProjectIdOrderByCreatedAtDesc(project.getId());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getId()).isEqualTo(row.getId());
    }

    @Test
    void truncatesPayloadsAboveCap() {
        Project project = data.project().named("snapshot-truncate").create();

        // 400KB of 'A' inside a JSON string field.
        StringBuilder big = new StringBuilder(400 * 1024 + 32);
        big.append("AAA");
        for (int i = 0; i < 400 * 1024; i++) {
            big.append('A');
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("blob", big.toString());

        Optional<ExecutionSnapshot> written = snapshotWriter.write(
                SnapshotWriter.SnapshotRequest.builder()
                        .projectId(project.getId())
                        .eventType("BIG")
                        .payload(payload)
                        .build());

        assertThat(written).isPresent();
        ExecutionSnapshot row = written.get();
        assertThat(row.isTruncated()).isTrue();
        // True size is the pre-truncation byte count.
        assertThat(row.getSizeBytes()).isGreaterThan(SnapshotWriter.MAX_INLINE_SIZE_BYTES);
        // Stored bytes are at or below the cap.
        int storedBytes = row.getPayloadJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertThat(storedBytes).isLessThanOrEqualTo(SnapshotWriter.MAX_INLINE_SIZE_BYTES);
    }

    @Test
    void samplingRateZeroDropsTheRow() {
        Project project = data.project().named("snapshot-sampled-out").create();
        project.setSnapshotSamplingRate(BigDecimal.ZERO.setScale(3, RoundingMode.UNNECESSARY));
        projectRepository.save(project);

        Optional<ExecutionSnapshot> written = snapshotWriter.write(
                SnapshotWriter.SnapshotRequest.builder()
                        .projectId(project.getId())
                        .eventType("DROPPED")
                        .payload(Map.of("x", 1))
                        .build());

        assertThat(written).isEmpty();
        assertThat(snapshotRepository.findByProjectIdOrderByCreatedAtDesc(project.getId()))
                .isEmpty();
    }

    @Test
    void persistsSourceAttribution() {
        Project project = data.project().named("snapshot-attribution").create();
        java.util.UUID serviceAccountId = java.util.UUID.randomUUID();
        java.util.UUID userId = java.util.UUID.randomUUID();

        Optional<ExecutionSnapshot> written = snapshotWriter.write(
                SnapshotWriter.SnapshotRequest.builder()
                        .projectId(project.getId())
                        .eventType("CONVERSATION_TURN_DISPATCHED")
                        .source("EXTERNAL_API")
                        .serviceAccountId(serviceAccountId)
                        .externalUserRef("ext-user-123")
                        .userId(userId)
                        .payload(Map.of("x", 1))
                        .build());

        assertThat(written).isPresent();
        ExecutionSnapshot row = written.get();
        assertThat(row.getSource()).isEqualTo("EXTERNAL_API");
        assertThat(row.getServiceAccountId()).isEqualTo(serviceAccountId);
        assertThat(row.getExternalUserRef()).isEqualTo("ext-user-123");
        assertThat(row.getUserId()).isEqualTo(userId);

        ExecutionSnapshot reloaded = snapshotRepository.findById(row.getId()).orElseThrow();
        assertThat(reloaded.getSource()).isEqualTo("EXTERNAL_API");
        assertThat(reloaded.getServiceAccountId()).isEqualTo(serviceAccountId);
        assertThat(reloaded.getExternalUserRef()).isEqualTo("ext-user-123");
        assertThat(reloaded.getUserId()).isEqualTo(userId);
    }

    @Test
    void leavesAttributionNullForNonConversationEvents() {
        Project project = data.project().named("snapshot-no-attribution").create();

        Optional<ExecutionSnapshot> written = snapshotWriter.write(
                SnapshotWriter.SnapshotRequest.builder()
                        .projectId(project.getId())
                        .eventType("WORKFLOW_STEP")
                        .payload(Map.of("x", 1))
                        .build());

        assertThat(written).isPresent();
        ExecutionSnapshot row = written.get();
        assertThat(row.getSource()).isNull();
        assertThat(row.getServiceAccountId()).isNull();
        assertThat(row.getExternalUserRef()).isNull();
        assertThat(row.getUserId()).isNull();
    }
}
