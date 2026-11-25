package ai.myrmec.engine.testing;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.AgentHost;
import ai.myrmec.engine.agent.AgentHostCreationResult;
import ai.myrmec.engine.agent.AgentProfile;
import ai.myrmec.engine.agent.AgentProfileRepository;
import ai.myrmec.engine.agent.AgentHostRepository;
import ai.myrmec.engine.team.Team;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test that drives {@link TestDataBuilder} through every public
 * builder once and verifies the produced fixtures land in the database.
 *
 * <p>Doubles as live documentation of the TestDataBuilder usage pattern —
 * any feature-phase test that needs team/profile/agent fixtures can copy
 * this shape.</p>
 */
@Transactional
class TestDataBuilderIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AgentProfileRepository agentProfileRepository;

    @Autowired
    private AgentHostRepository agentRepository;

    @Test
    void buildsProjectProfileAndAgentChain() {
        team team = data.team()
                .named("td-build-team")
                .withDescription("Built by TestDataBuilderIT")
                .create();
        assertThat(team.getId()).isNotNull();
        assertThat(TeamRepository.findById(team.getId())).isPresent();

        AgentProfile profile = data.agentProfile()
                .named("td-build-profile")
                .withSystemPrompt("system")
                .create();
        assertThat(profile.getId()).isNotNull();
        assertThat(agentProfileRepository.findById(profile.getId())).isPresent();

        AgentHostCreationResult result = data.agent()
                .named("td-build-agent")
                .inTeam(team)
                .create();

        AgentHost agent = result.agent();
        assertThat(agent.getId()).isNotNull();
        assertThat(agent.getTeamId()).isEqualTo(team.getId());
        assertThat(result.registrationKey()).startsWith("myr_agent_");
        assertThat(agentRepository.findById(agent.getId())).isPresent();
    }

    @Test
    void monotonicSuffixAvoidsNameCollisions() {
        Team A = data.team().named("collision-check").create();
        Team B = data.team().named("collision-check").create();

        assertThat(a.getName()).isNotEqualTo(b.getName());
        assertThat(a.getName()).startsWith("collision-check-");
        assertThat(b.getName()).startsWith("collision-check-");
    }
}
