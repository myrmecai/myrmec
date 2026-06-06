package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.HealthStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response DTO for model health status.
 */
@Data
@Builder
public class ModelHealthResponse {

    private String code;
    private HealthStatus status;
    private Instant lastCheck;
    private Map<String, Object> metrics;
    private List<HealthHistoryEntry> history;

    @Data
    @Builder
    public static class HealthHistoryEntry {
        private Instant timestamp;
        private HealthStatus status;
    }
}
