package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.exception.ErrorResponse;
import ai.myrmec.engine._system.security.AgentPrincipal;
import ai.myrmec.engine.agent.dto.AgentInfoResponse;
import ai.myrmec.engine.agent.dto.HeartbeatRequest;
import ai.myrmec.engine.agent.dto.HeartbeatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Controller for agent instance operations.
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Agent", description = "Agent heartbeat and status operations")
public class AgentController {

    private final AgentService agentService;

    @Operation(
            summary = "Record agent heartbeat",
            description = "Record a heartbeat from the authenticated agent instance. Used to track instance availability."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Heartbeat recorded successfully"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired access token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestBody HeartbeatRequest request) {

        HeartbeatResponse response = agentService.recordHeartbeat(principal.getInstanceId(), request);
        return ResponseEntity.ok(response);
    }

    @Operation(
            summary = "Get current agent instance info",
            description = "Get information about the currently authenticated agent instance."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agent instance info retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Invalid or expired access token",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Agent instance not found",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<AgentInfoResponse> me(@AuthenticationPrincipal AgentPrincipal principal) {
        AgentInstance instance = agentService.getInstance(principal.getInstanceId());
        return ResponseEntity.ok(AgentInfoResponse.from(instance));
    }
}
