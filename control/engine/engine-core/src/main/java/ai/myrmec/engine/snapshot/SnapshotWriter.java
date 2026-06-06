package ai.myrmec.engine.snapshot;

import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Phase 9a — fire-and-forget writer for {@link ExecutionSnapshot} rows.
 *
 * <p>Designed to be called from any execution path without polluting
 * the caller's transaction: every write runs in its own
 * {@link Propagation#REQUIRES_NEW} so a snapshot failure can NEVER
 * abort the caller's work. The caller hands in an
 * {@link SnapshotRequest} and the writer handles serialisation,
 * sampling, 256KB truncation, and the SHA256 fingerprint (for the
 * future tiered-storage migration).</p>
 *
 * <p>The whole component can be globally disabled (e.g. for tests that
 * don't care about snapshots) via {@code myrmec.snapshots.enabled},
 * but the default in production AND tests is on so the history
 * accumulates from V1 day one.</p>
 */
@Service
@Slf4j
public class SnapshotWriter {

    /** Hard ceiling per row to keep page-fetch costs bounded. */
    public static final int MAX_INLINE_SIZE_BYTES = 256 * 1024;

    private final ExecutionSnapshotRepository snapshotRepository;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public SnapshotWriter(
            ExecutionSnapshotRepository snapshotRepository,
            ProjectRepository projectRepository,
            ObjectMapper objectMapper,
            @Value("${myrmec.snapshots.enabled:true}") boolean enabled) {
        this.snapshotRepository = snapshotRepository;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    /**
     * Persist a snapshot row. Returns the persisted entity or an empty
     * Optional when the row was dropped (sampling miss or writer
     * disabled). NEVER throws — internal exceptions are logged and
     * swallowed because the caller's primary work must not be aborted
     * by a snapshot failure.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExecutionSnapshot> write(SnapshotRequest req) {
        if (!enabled) {
            return Optional.empty();
        }
        if (req == null || req.getProjectId() == null || req.getEventType() == null) {
            log.warn("Snapshot write rejected — projectId + eventType required");
            return Optional.empty();
        }
        try {
            // Sampling check (per-project knob). Default 1.0 = always keep.
            Project project = projectRepository.findById(req.getProjectId()).orElse(null);
            if (project != null) {
                java.math.BigDecimal rate = project.getSnapshotSamplingRate();
                if (rate != null && rate.compareTo(java.math.BigDecimal.ONE) < 0) {
                    double roll = ThreadLocalRandom.current().nextDouble();
                    if (roll > rate.doubleValue()) {
                        return Optional.empty();
                    }
                }
            }

            String json = serialise(req.getPayload());
            long sizeBytes = json == null ? 0 : json.getBytes(StandardCharsets.UTF_8).length;
            boolean truncated = false;
            String stored = json;
            if (json != null && sizeBytes > MAX_INLINE_SIZE_BYTES) {
                // Truncate at byte boundary then re-decode safely.
                byte[] raw = json.getBytes(StandardCharsets.UTF_8);
                stored = new String(raw, 0, MAX_INLINE_SIZE_BYTES, StandardCharsets.UTF_8);
                truncated = true;
            }
            String sha = json == null ? null : sha256Hex(json);

            ExecutionSnapshot row = new ExecutionSnapshot();
            row.setProjectId(req.getProjectId());
            row.setAgentId(req.getAgentId());
            row.setConversationId(req.getConversationId());
            row.setMessageId(req.getMessageId());
            row.setWorkflowId(req.getWorkflowId());
            row.setStepRunId(req.getStepRunId());
            row.setEventType(req.getEventType());
            row.setPayloadJson(stored);
            row.setPayloadSha256(sha);
            row.setSizeBytes(sizeBytes);
            row.setTruncated(truncated);
            row.setSampled(true);
            return Optional.of(snapshotRepository.save(row));
        } catch (Exception e) {
            log.warn("Snapshot write failed for project {} event {}: {}",
                    req.getProjectId(), req.getEventType(), e.getMessage(), e);
            return Optional.empty();
        }
    }

    private String serialise(Object payload) throws JsonProcessingException {
        if (payload == null) {
            return null;
        }
        if (payload instanceof String s) {
            return s;
        }
        return objectMapper.writeValueAsString(payload);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /** Inputs to {@link SnapshotWriter#write}. Use the builder. */
    @lombok.Value
    @Builder
    public static class SnapshotRequest {
        UUID projectId;
        String eventType;
        Object payload;
        UUID agentId;
        UUID conversationId;
        UUID messageId;
        UUID workflowId;
        UUID stepRunId;
    }
}
