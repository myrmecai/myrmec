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
 * <p>
 * A conversation pins to one {@link ai.myrmec.engine.team.Team}
 * and optionally to one agent. Messages append to
 * {@link ConversationMessage}; ACL lives on
 * {@link ConversationParticipant}.
 * </p>
 */
@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
public class Conversation {

    /**
     * Lifecycle state (2026-09-21 close/archive design section 2.1):
     * ACTIVE - live chat; CLOSED - user-ended terminal state, transcript
     * stays readable but no new messages; ARCHIVED - soft delete that
     * subsumes the old DELETED (no un-archive). A row-level IDLE state is
     * deliberately absent - idle is a live signal from the session
     * allocation layer, and the row stays ACTIVE so the next message
     * transparently re-offers a fresh session.
     */
    public enum Status {
        ACTIVE, ARCHIVED, CLOSED
    }

    /** Where the conversation was started from (#96, External API seam). */
    public enum Source {
        WEB_UI, EXTERNAL_API
    }

    /**
     * Position of this conversation inside a moderated thread (#96/#100 seam).
     * V1 is always {@link #STANDALONE}; V2 distinguishes the public-facing and
     * private moderator halves.
     */
    public enum RoleInThread {
        STANDALONE, PUBLIC_FACING, MODERATOR_PRIVATE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "team_id", nullable = false)
    private UUID teamId;

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

    @Column(name = "title", length = 500)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** When the conversation was closed (USER_ENDED or implicit ARCHIVED close). */
    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Why the conversation closed: USER_ENDED (explicit close), ARCHIVED
     * (implicit close of an ACTIVE row at archive time). Null while ACTIVE.
     */
    @Column(name = "close_reason", length = 64)
    private String closeReason;

    /** User who closed the conversation. Null for engine-driven closes. */
    @Column(name = "closed_by")
    private UUID closedBy;

    /** When the conversation was archived. Null until archived. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    /** Why the conversation was archived (USER_ARCHIVED). Null until archived. */
    @Column(name = "archive_reason", length = 64)
    private String archiveReason;

    /** User who archived the conversation. Null for engine-driven archives. */
    @Column(name = "archived_by")
    private UUID archivedBy;

    /** Per-conversation pinned facts (#9), as free-form text the engine appends. */
    @Column(name = "pinned_facts", columnDefinition = "text")
    private String pinnedFacts;

    /**
     * Live context size — the main model's most recent prompt-token count,
     * tracked from MODEL_USAGE execution events. Null until the first
     * main-model call reports usage. Feeds the header's context bagel.
     */
    @Column(name = "context_tokens")
    private Integer contextTokens;

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

    /**
     * Position of this conversation in a moderated thread (#96/#100). V1 always
     * STANDALONE.
     */
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

    @Column(name = "current_session_id")
    private UUID currentSessionId;

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
