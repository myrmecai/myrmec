package ai.myrmec.engine.agent.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class HeartbeatRequest {

    private String status;
    private UUID currentTaskId;
}
