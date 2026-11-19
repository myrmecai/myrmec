package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.tool.RiskClass;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.tool.ToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Combines a Team's HITL policy with a tool's risk class to answer
 * the one question every agent + UI consumer needs: <em>do I need a
 * human approval before running this?</em>
 *
 * <p>The rule (Phase 7b) is intentionally simple:</p>
 * <pre>
 *   approvalRequired = Team.auto_hitl_on_destructive
 *                       AND tool.risk_class IN (DESTRUCTIVE, IRREVERSIBLE)
 * </pre>
 *
 * <p>Phase 7e will layer per-step approver pinning, quota-based gating,
 * and other extensions on top.</p>
 */
@Service
@RequiredArgsConstructor
public class HitlPolicyService {

    private final TeamRepository teamRepository;
    private final ToolRepository toolRepository;
    private final AgentHostRepository agentRepository;

    public record Decision(
            UUID teamId,
            String toolCode,
            RiskClass riskClass,
            boolean autoHitlOnDestructive,
            boolean approvalRequired) { }

    @Transactional(readOnly = true)
    public Decision evaluate(UUID teamId, String toolCode) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("team", teamId));
        Tool tool = toolRepository.findById(toolCode)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", toolCode));

        RiskClass rc = tool.getRiskClass() != null ? tool.getRiskClass() : RiskClass.SAFE;
        boolean destructive = rc == RiskClass.DESTRUCTIVE || rc == RiskClass.IRREVERSIBLE;
        boolean approvalRequired = team.isAutoHitlOnDestructive() && destructive;

        return new Decision(
                teamId,
                tool.getCode(),
                rc,
                team.isAutoHitlOnDestructive(),
                approvalRequired);
    }

    /**
     * Convenience overload — looks up the agent's pinned Team so
     * the agent SDK can call this without first round-tripping through
     * {@code /api/v1/agent/me}.
     */
    @Transactional(readOnly = true)
    public Decision evaluateForAgent(UUID agentId, String toolCode) {
        AgentHost agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", agentId));
        if (agent.getTeamId() == null) {
            // Unpinned agent — there is no Team policy to evaluate,
            // so we honour the tool's classification only. SAFE/WRITE
            // remain auto-allowed; destructive tools demand explicit
            // pinning before they can run.
            Tool tool = toolRepository.findById(toolCode)
                    .orElseThrow(() -> new ResourceNotFoundException("Tool", toolCode));
            RiskClass rc = tool.getRiskClass() != null ? tool.getRiskClass() : RiskClass.SAFE;
            boolean destructive = rc == RiskClass.DESTRUCTIVE || rc == RiskClass.IRREVERSIBLE;
            return new Decision(null, tool.getCode(), rc, false, destructive);
        }
        return evaluate(agent.getTeamId(), toolCode);
    }
}
