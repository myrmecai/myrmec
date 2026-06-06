package ai.myrmec.engine.group;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Group entity — governance container above projects.
 *
 * <p>v1 is flat in the UI: every group has {@code parentGroupId == null}.
 * The column is reserved nullable so future nesting becomes additive
 * (closure / recursive CTE walk on {@code parent_group_id}).
 *
 * <p>A well-known seeded group with id
 * {@code 00000000-0000-0000-0000-000000000001} ("Default") backstops projects
 * that were not assigned an explicit group.
 */
@Entity
@Table(name = "groups")
@Getter
@Setter
@NoArgsConstructor
public class Group {

    /** Fixed UUID of the seeded "Default" group — see Liquibase changeset. */
    public static final UUID DEFAULT_GROUP_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 200)
    private String name;

    /**
     * Reserved for future nesting. Always {@code null} in v1.
     */
    @Column(name = "parent_group_id")
    private UUID parentGroupId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
