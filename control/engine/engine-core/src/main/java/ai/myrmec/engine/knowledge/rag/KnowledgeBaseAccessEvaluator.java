package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.group.GroupRepository;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.spi.retrieval.KnowledgeBaseScope;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Retrieval ACL for agents (#27). Decides whether an agent — identified by its
 * owning {@link AgentHost}'s project — may pull chunks from a {@link KnowledgeBase}.
 *
 * <p>Reachability mirrors the scope-cascade used everywhere else in the platform:</p>
 * <ul>
 *   <li>{@link KnowledgeBaseScope#SYSTEM} — visible to every agent.</li>
 *   <li>{@link KnowledgeBaseScope#PROJECT} — only the owning project's agents.</li>
 *   <li>{@link KnowledgeBaseScope#GROUP} — agents whose project's group is the
 *       KB's group or a descendant of it (ancestor walk on the project's group).</li>
 * </ul>
 *
 * <p>An agent host with no project ({@code projectId == null}) can only reach
 * SYSTEM knowledge bases. This prevents a host-level agent from reading any
 * project- or group-scoped data it was never granted.</p>
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseAccessEvaluator {

    private final AgentHostRepository agentHostRepository;
    private final ProjectRepository projectRepository;
    private final GroupRepository groupRepository;

    /**
     * Project that owns the agent's host, or {@code null} when the host is not
     * bound to a project (or the host id does not resolve).
     */
    @Transactional(readOnly = true)
    public UUID resolveAgentProjectId(UUID agentHostId) {
        if (agentHostId == null) {
            return null;
        }
        return agentHostRepository.findById(agentHostId)
                .map(AgentHost::getProjectId)
                .orElse(null);
    }

    /**
     * Whether an agent in {@code agentProjectId} (nullable) may retrieve from
     * {@code kb}. A {@code null} KB is never reachable.
     */
    @Transactional(readOnly = true)
    public boolean canRetrieve(KnowledgeBase kb, UUID agentProjectId) {
        if (kb == null) {
            return false;
        }
        return switch (kb.getScope()) {
            case SYSTEM -> true;
            case PROJECT -> agentProjectId != null && agentProjectId.equals(kb.getProjectId());
            case GROUP -> reachableGroup(agentProjectId, kb.getGroupId());
        };
    }

    private boolean reachableGroup(UUID agentProjectId, UUID kbGroupId) {
        if (agentProjectId == null || kbGroupId == null) {
            return false;
        }
        UUID projectGroupId = projectRepository.findById(agentProjectId)
                .map(Project::getGroupId)
                .orElse(null);
        if (projectGroupId == null) {
            return false;
        }
        if (projectGroupId.equals(kbGroupId)) {
            return true;
        }
        return groupRepository.findAncestorIds(projectGroupId).contains(kbGroupId);
    }
}
