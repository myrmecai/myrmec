package ai.myrmec.engine.spi.retrieval;

/**
 * Scope of a knowledge base.
 *
 * <p>Determines visibility and ACL evaluation for retrieval queries.
 * Engine retrieval pipelines combine results from every accessible scope
 * for the calling user/Team And dedupe at the document level.</p>
 */
public enum KnowledgeBaseScope {

    /**
     * Team-scoped knowledge base. Visible only to users with access to
     * the owning Team (delegated to {@code TeamAccessEvaluator}).
     * {@code teamId} must be set; {@code groupId} must be null.
     */
    Team,

    /**
     * Group-scoped knowledge base. Visible to every Team in the owning
     * group and its descendant groups, subject to per-user group ACL.
     * {@code groupId} must be set; {@code teamId} must be null.
     */
    GROUP,

    /**
     * System-wide knowledge base. Visible to every authenticated user.
     * Both {@code teamId} and {@code groupId} must be null. Reserved
     * for organisational standards (security policies, code conventions,
     * playbooks). Write access is restricted to {@code ORG_ADMIN}.
     */
    SYSTEM
}
