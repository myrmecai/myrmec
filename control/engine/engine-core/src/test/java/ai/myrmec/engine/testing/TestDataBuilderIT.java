package ai.myrmec.engine.testing;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.Agent;
import ai.myrmec.engine.agent.AgentCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentRepository;
import ai.myrmec.engine.project.Project;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that drives {@link TestDataBuilder} through every public
 * builder once and verifies the produced fixtures land in the database.
 *
 * <p>Doubles as live documentation of the TestDataBuilder usage pattern —
 * any feature-phase test that needs project/profile/agent fixtures can copy
 * this shape.</p>
 */
@Transactional
class TestDataBuilderIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AgentProfileRepository agentProfileRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Test
    void buildsProjectProfileAndAgentChain() {
        Project project = data.project()
                .named("td-build-project")
                .withDescription("Built by TestDataBuilderIT")
                .create();
        assertThat(project.getId()).isNotNull();
        assertThat(projectRepository.findById(project.getId())).isPresent();

        AgentProfile profile = data.agentProfile()
                .named("td-build-profile")
                .withSystemPrompt("system")
                .create();
        assertThat(profile.getId()).isNotNull();
        assertThat(agentProfileRepository.findById(profile.getId())).isPresent();

        AgentCreationResult result = data.agent()
                .named("td-build-agent")
                .withProfile(profile)
                .inProject(project)
                .create();

        Agent agent = result.agent();
        assertThat(agent.getId()).isNotNull();
        assertThat(agent.getProfileId()).isEqualTo(profile.getId());
        assertThat(agent.getProjectId()).isEqualTo(project.getId());
        assertThat(result.registrationKey()).startsWith("myr_agent_");
        assertThat(agentRepository.findById(agent.getId())).isPresent();
    }

    @Test
    void monotonicSuffixAvoidsNameCollisions() {
        Project a = data.project().named("collision-check").create();
        Project b = data.project().named("collision-check").create();

        assertThat(a.getName()).isNotEqualTo(b.getName());
        assertThat(a.getName()).startsWith("collision-check-");
        assertThat(b.getName()).startsWith("collision-check-");
    }
}
