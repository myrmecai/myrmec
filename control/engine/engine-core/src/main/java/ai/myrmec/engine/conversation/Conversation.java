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

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** Null until the first agent turn lands; pinned thereafter. */
    @Column(name = "agent_id")
    private UUID agentId;

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

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
