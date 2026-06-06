package ai.myrmec.engine.agent;

import ai.myrmec.engine._system.common.JsonListConverter;
import ai.myrmec.engine.tool.Tool;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Agent Profile entity - template defining capabilities and tooling.
 * Multiple agents can use the same profile.
 */
@Entity
@Table(name = "agent_profiles")
@Getter
@Setter
@NoArgsConstructor
public class AgentProfile {

    public enum Status {
        ACTIVE, INACTIVE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Unique profile name (e.g., "Python K8s Worker").
     */
    @Column(name = "name", nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    /**
     * Runtime requirements as JSON array.
     * Examples: ["python:>=3.11", "docker", "gpu:nvidia", "16gb-ram"]
     */
    @Convert(converter = JsonListConverter.class)
    @Column(name = "capabilities", nullable = false)
    private List<String> capabilities;

    /**
     * Available integrations as JSON array.
     * Examples: ["kubectl", "helm", "aws-cli", "git"]
     * @deprecated Use the tools relationship instead
     */
    @Convert(converter = JsonListConverter.class)
    @Column(name = "supported_tools", nullable = false)
    private List<String> supportedTools;

    /**
     * Tools assigned to this profile via junction table.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "agent_profile_tools",
        joinColumns = @JoinColumn(name = "profile_id"),
        inverseJoinColumns = @JoinColumn(name = "tool_code")
    )
    private Set<Tool> tools = new HashSet<>();

    /**
     * Default model code for agents using this profile.
     */
    @Column(name = "default_model", length = 50)
    private String defaultModel;

    /**
     * System prompt for agents using this profile.
     */
    @Column(name = "system_prompt", columnDefinition = "text")
    private String systemPrompt;

    /**
     * System profiles cannot be deleted.
     */
    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
