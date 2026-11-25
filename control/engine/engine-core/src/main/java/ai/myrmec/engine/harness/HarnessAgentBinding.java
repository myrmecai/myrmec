package ai.myrmec.engine.harness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "harness_agent_bindings")
@Getter
@Setter
@NoArgsConstructor
public class HarnessAgentBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "harness_id", nullable = false)
    private UUID harnessId;

    @Column(name = "agent_profile_id", nullable = false)
    private UUID agentProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false, length = 40)
    private AgentRoleType roleType;

    @Column(name = "agent_alias", nullable = false, length = 100)
    private String agentAlias;

    @Column(name = "prompt_addendum", columnDefinition = "text")
    private String promptAddendum;
}