package ai.myrmec.engine.conversation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Conversation ACL row — grants one user one role in one conversation.
 *
 * <p>The user who owns the conversation gets an {@code OWNER} row at
 * create time; subsequent invitations append {@code EDITOR} or
 * {@code VIEWER} rows. Multi-viewer fan-out (Phase 6c) is gated on the
 * presence of any matching row.</p>
 */
@Entity
@Table(name = "conversation_participants")
@Getter
@Setter
@NoArgsConstructor
public class ConversationParticipant {

    public enum Role { OWNER, EDITOR, VIEWER }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role = Role.VIEWER;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private Instant joinedAt;

    @PrePersist
    void onCreate() {
        joinedAt = Instant.now();
    }
}
