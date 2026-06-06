package ai.myrmec.engine.spi.retrieval;

/**
 * Scope of a knowledge base.
 *
 * <p>Determines visibility and ACL evaluation for retrieval queries.
 * Engine retrieval pipelines combine results from every accessible scope
 * for the calling user/project and dedupe at the document level.</p>
 */
public enum KnowledgeBaseScope {

    /**
     * Project-scoped knowledge base. Visible only to users with access to
     * the owning project (delegated to {@code ProjectAccessEvaluator}).
     * {@code projectId} must be set; {@code groupId} must be null.
     */
    PROJECT,

    /**
     * Group-scoped knowledge base. Visible to every project in the owning
     * group and its descendant groups, subject to per-user group ACL.
     * {@code groupId} must be set; {@code projectId} must be null.
     */
    GROUP,

    /**
     * System-wide knowledge base. Visible to every authenticated user.
     * Both {@code projectId} and {@code groupId} must be null. Reserved
     * for organisational standards (security policies, code conventions,
     * playbooks). Write access is restricted to {@code ORG_ADMIN}.
     */
    SYSTEM
}
