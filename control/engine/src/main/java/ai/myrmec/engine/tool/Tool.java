package ai.myrmec.engine.tool;

import ai.myrmec.engine._system.common.JsonMapConverter;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

/**
 * Tool registry entity.
 * Represents a tool that can be assigned to agent profiles for use in workflows.
 */
@Entity
@Table(name = "tools")
@Getter
@Setter
@NoArgsConstructor
public class Tool {

    /**
     * Unique tool identifier (e.g., "github", "filesystem", "postgres").
     */
    @Id
    @Column(name = "code", nullable = false, updatable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "tool_type", nullable = false, length = 20)
    private ToolType toolType;

    /**
     * JSON Schema defining the configuration required for this tool.
     */
    @Convert(converter = JsonMapConverter.class)
    @Column(name = "config_schema")
    private Map<String, Object> configSchema;

    @Column(name = "docs_url", length = 500)
    private String docsUrl;

    /**
     * System tools are pre-installed and cannot be deleted.
     */
    @Column(name = "is_system", nullable = false)
    private boolean isSystem = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ToolStatus status = ToolStatus.ACTIVE;

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
