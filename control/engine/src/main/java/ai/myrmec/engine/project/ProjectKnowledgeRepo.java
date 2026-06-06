package ai.myrmec.engine.project;

import ai.myrmec.engine._system.common.JsonListConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Knowledge source repository attached to a project.
 *
 * Multiple knowledge repos can be configured per project. Repo contents are
 * fetched on-demand by the control plane at task dispatch time and merged
 * with manually-authored knowledge documents into the task context.
 */
@Entity
@Table(name = "project_knowledge_repos")
@Getter
@Setter
@NoArgsConstructor
public class ProjectKnowledgeRepo {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "repo_url", nullable = false, length = 500)
    private String repoUrl;

    @Column(name = "branch", nullable = false, length = 100)
    private String branch = "main";

    /**
     * Glob patterns (relative to repo root) for files to ingest as knowledge.
     * When null/empty, defaults to docs/**&#47;*.md and .github/instructions/**&#47;*.md.
     */
    @Convert(converter = JsonListConverter.class)
    @Column(name = "instruction_paths")
    private List<String> instructionPaths;

    /**
     * Optional reference to a {@link ai.myrmec.engine.secret.Secret} usable from
     * this project (global or project-scoped). When null an unauthenticated
     * clone is attempted. Acceptable credential types: BEARER_TOKEN,
     * USERNAME_PASSWORD, SSL_PRIVATE_KEY.
     */
    @Column(name = "credential_secret_id")
    private UUID credentialSecretId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
