package ai.myrmec.engine.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversation — durable container for an interactive chat. Maps 1:1 with
 * the {@code conversations} table.
 *
 * <p>A conversation pins to one {@link ai.myrmec.engine.project.Project}
 * and optionally to one agent. Messages append to
 * {@link ConversationMessage}; ACL lives on
 * {@link ConversationParticipant}.</p>
 */
@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
public class Conversation {

    public enum Status { ACTIVE, ARCHIVED, DELETED }

    /** Where the conversation was started from (#96, External API seam). */
    public enum Source { WEB_UI, EXTERNAL_API }

    /**
     * Position of this conversation inside a moderated thread (#96/#100 seam).
     * V1 is always {@link #STANDALONE}; V2 distinguishes the public-facing and
     * private moderator halves.
     */
    public enum RoleInThread { STANDALONE, PUBLIC_FACING, MODERATOR_PRIVATE }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** Null until the first agent turn lands; pinned thereafter. */
    @Column(name = "agent_id")
    private UUID agentId;

    /**
     * Current/last serving Host (slice 4a, denormalized durable machine).
     * Unlike the ephemeral worker {@code agentId} this survives across bind
     * attempts; the ephemeral worker is deliberately not denormalized here
     * (see conversation-observability §4). Null until first bind.
     */
    @Column(name = "agent_host_id")
    private UUID agentHostId;

    /**
     * Replica holding the client's live conversation connection (slice 4a).
     * Null when no client is connected; on a single node it resolves to
     * "self". The cross-node router (slice 4b) reads this to forward
     * streamed output to the owning replica.
     */
    @Column(name = "home_node_id", length = 255)
    private String homeNodeId;

    /**
     * Durable Assistant identity for this conversation (#92). Null for
     * legacy / profile-only chats started before the Assistant entity.
     */
    @Column(name = "assistant_id")
    private UUID assistantId;

    /**
     * Pinned immutable {@code assistant_version} for the life of the session
     * (assistant-entity.md §5.6). Null for legacy chats.
     */
    @Column(name = "assistant_version_id")
    private UUID assistantVersionId;

    /**
     * Pinned profile version. Forward seam until AgentProfile versioning
     * lands; null today.
     */
    @Column(name = "agent_profile_version_id")
    private UUID agentProfileVersionId;

    @Column(name = "title", length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** Per-conversation system-prompt override (#9). Null means inherit profile. */
    @Column(name = "system_prompt_override", columnDefinition = "text")
    private String systemPromptOverride;

    /** Per-conversation pinned facts (#9), as free-form text the engine appends. */
    @Column(name = "pinned_facts", columnDefinition = "text")
    private String pinnedFacts;

    /**
     * Where the conversation was started from (#96). Defaults to {@code WEB_UI};
     * the External API (#95) creates {@code EXTERNAL_API} sessions.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private Source source = Source.WEB_UI;

    /**
     * Opaque caller-side end-user identity for {@code EXTERNAL_API} sessions
     * (#95, {@code X-Myrmec-End-User-Ref}). Null for WEB_UI chats.
     */
    @Column(name = "external_user_ref", length = 255)
    private String externalUserRef;

    /**
     * Forward seam (#96): the service account / integration that opened the
     * session. No FK yet — {@code service_accounts} lands with #95.
     */
    @Column(name = "service_account_id")
    private UUID serviceAccountId;

    /**
     * Moderated-thread seam (#96/#100): the public session a private moderator
     * session hangs off. Null for standalone conversations.
     */
    @Column(name = "parent_conversation_id")
    private UUID parentConversationId;

    /** Position of this conversation in a moderated thread (#96/#100). V1 always STANDALONE. */
    @Enumerated(EnumType.STRING)
    @Column(name = "role_in_thread", nullable = false, length = 30)
    private RoleInThread roleInThread = RoleInThread.STANDALONE;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private ai.myrmec.engine.context.ContextSnapshot contextSnapshot;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
