package ai.myrmec.engine.agent.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 9d — read-only agent health endpoint for the agent admin UI.
 *
 * <p>Same role requirement as {@code AgentAdminController}: platform
 * admins or editors. Returns aggregated health from
 * {@link AgentHealthService}.</p>
 */
@RestController
@RequestMapping("/api/v1/admin/agents")
@RequiredArgsConstructor
@PreAuthorize("hasRole('PLATFORM_ADMIN') or hasRole('EDITOR')")
@Tag(name = "Agents (Admin)", description = "Agent management operations")
public class AgentHealthController {

    private final AgentHealthService healthService;

    @Operation(summary = "Health snapshot for one agent")
    @GetMapping("/{agentId}/health")
    public AgentHealthSnapshot health(@PathVariable UUID agentId) {
        return healthService.snapshot(agentId);
    }
}
