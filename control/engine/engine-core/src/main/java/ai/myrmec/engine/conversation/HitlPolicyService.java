package ai.myrmec.engine.conversation;

import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.tool.RiskClass;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.tool.ToolRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Combines a project's HITL policy with a tool's risk class to answer
 * the one question every agent + UI consumer needs: <em>do I need a
 * human approval before running this?</em>
 *
 * <p>The rule (Phase 7b) is intentionally simple:</p>
 * <pre>
 *   approvalRequired = project.auto_hitl_on_destructive
 *                       AND tool.risk_class IN (DESTRUCTIVE, IRREVERSIBLE)
 * </pre>
 *
 * <p>Phase 7e will layer per-step approver pinning, quota-based gating,
 * and other extensions on top.</p>
 */
@Service
@RequiredArgsConstructor
public class HitlPolicyService {

    private final ProjectRepository projectRepository;
    private final ToolRepository toolRepository;
    private final AgentRepository agentRepository;

    public record Decision(
            UUID projectId,
            String toolCode,
            RiskClass riskClass,
            boolean autoHitlOnDestructive,
            boolean approvalRequired) { }

    @Transactional(readOnly = true)
    public Decision evaluate(UUID projectId, String toolCode) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", projectId));
        Tool tool = toolRepository.findById(toolCode)
                .orElseThrow(() -> new ResourceNotFoundException("Tool", toolCode));

        RiskClass rc = tool.getRiskClass() != null ? tool.getRiskClass() : RiskClass.SAFE;
        boolean destructive = rc == RiskClass.DESTRUCTIVE || rc == RiskClass.IRREVERSIBLE;
        boolean approvalRequired = project.isAutoHitlOnDestructive() && destructive;

        return new Decision(
                projectId,
                tool.getCode(),
                rc,
                project.isAutoHitlOnDestructive(),
                approvalRequired);
    }

    /**
     * Convenience overload — looks up the agent's pinned project so
     * the agent SDK can call this without first round-tripping through
     * {@code /api/v1/agent/me}.
     */
    @Transactional(readOnly = true)
    public Decision evaluateForAgent(UUID agentId, String toolCode) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", agentId));
        if (agent.getProjectId() == null) {
            // Unpinned agent — there is no project policy to evaluate,
            // so we honour the tool's classification only. SAFE/WRITE
            // remain auto-allowed; destructive tools demand explicit
            // pinning before they can run.
            Tool tool = toolRepository.findById(toolCode)
                    .orElseThrow(() -> new ResourceNotFoundException("Tool", toolCode));
            RiskClass rc = tool.getRiskClass() != null ? tool.getRiskClass() : RiskClass.SAFE;
            boolean destructive = rc == RiskClass.DESTRUCTIVE || rc == RiskClass.IRREVERSIBLE;
            return new Decision(null, tool.getCode(), rc, false, destructive);
        }
        return evaluate(agent.getProjectId(), toolCode);
    }
}
