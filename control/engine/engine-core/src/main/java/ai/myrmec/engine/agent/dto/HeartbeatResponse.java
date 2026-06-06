package ai.myrmec.engine.agent.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class HeartbeatResponse {

    private boolean ack;
    private Instant serverTime;
}
