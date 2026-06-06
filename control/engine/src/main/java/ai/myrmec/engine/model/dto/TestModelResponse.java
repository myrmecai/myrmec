package ai.myrmec.engine.model.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Response DTO for model connection test results.
 */
@Data
@Builder
public class TestModelResponse {

    private String status;
    private long latencyMs;
    private String message;
    private Instant testedAt;
}
