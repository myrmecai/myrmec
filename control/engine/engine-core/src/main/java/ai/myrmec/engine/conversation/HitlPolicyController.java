package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.security.AgentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Read-only policy lookup endpoints. Both shapes return the same
 * {@link HitlPolicyService.Decision} so admin tooling, the UI and the
 * agent SDK speak one schema.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "HITL", description = "Human-in-the-loop policy lookups")
public class HitlPolicyController {

    private final HitlPolicyService hitlPolicyService;

    /**
     * Admin / UI lookup: "what would happen if project X tried to run tool Y?"
     * Anyone with project read access can call this — it's metadata, not
     * an action.
     */
    @Operation(summary = "Evaluate HITL policy for a project + tool")
    @GetMapping("/projects/{projectId}/hitl-policy")
    @PreAuthorize("@projectAccess.canView(#projectId, authentication)")
    public ResponseEntity<HitlPolicyService.Decision> evaluateForProject(
            @PathVariable UUID projectId,
            @RequestParam("toolCode") String toolCode) {
        return ResponseEntity.ok(hitlPolicyService.evaluate(projectId, toolCode));
    }

    /**
     * Agent-side lookup: the SDK calls this with its own bearer token
     * right before invoking a tool. Phase 7c will make this the gate
     * for {@code ctx.request_approval(...)}.
     */
    @Operation(summary = "Evaluate HITL policy for the calling agent + a tool")
    @GetMapping("/agent/hitl-policy")
    public ResponseEntity<HitlPolicyService.Decision> evaluateForAgent(
            @AuthenticationPrincipal AgentPrincipal principal,
            @RequestParam("toolCode") String toolCode) {
        return ResponseEntity.ok(hitlPolicyService.evaluateForAgent(principal.getAgentId(), toolCode));
    }
}
