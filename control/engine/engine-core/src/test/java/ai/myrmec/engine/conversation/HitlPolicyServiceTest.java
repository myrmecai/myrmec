package ai.myrmec.engine.conversation;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectRepository;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.tool.RiskClass;
import ai.myrmec.engine.tool.Tool;
import ai.myrmec.engine.tool.ToolRepository;
import ai.myrmec.engine.tool.ToolStatus;
import ai.myrmec.engine.tool.ToolType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7b — checks that the HITL policy decision is the simple
 * AND of <em>project opted-in</em> and <em>tool flagged destructive</em>.
 *
 * <p>The matrix matters because every later phase (UI badge, agent SDK
 * gate, scheduled sweeper) reuses the exact same boolean.</p>
 */
class HitlPolicyServiceTest extends IntegrationTestBase {

    @Autowired
    private HitlPolicyService hitlPolicyService;

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private ToolRepository toolRepository;

    @ParameterizedTest(name = "[{index}] project.autoHitl={0}, tool.risk={1} -> approvalRequired={2}")
    @CsvSource({
            "false, SAFE,         false",
            "false, WRITE,        false",
            "false, DESTRUCTIVE,  false",
            "false, IRREVERSIBLE, false",
            "true,  SAFE,         false",
            "true,  WRITE,        false",
            "true,  DESTRUCTIVE,  true",
            "true,  IRREVERSIBLE, true"
    })
    void evaluateReturnsTheExpectedDecisionForEveryCombination(
            boolean autoHitl, RiskClass riskClass, boolean expected) {

        Project project = data.project()
                .named("hitl-policy-" + UUID.randomUUID())
                .create();
        project.setAutoHitlOnDestructive(autoHitl);
        projectRepository.saveAndFlush(project);

        Tool tool = createTool("t-" + shortId(), riskClass);

        HitlPolicyService.Decision decision =
                hitlPolicyService.evaluate(project.getId(), tool.getCode());

        assertThat(decision.projectId()).isEqualTo(project.getId());
        assertThat(decision.toolCode()).isEqualTo(tool.getCode());
        assertThat(decision.riskClass()).isEqualTo(riskClass);
        assertThat(decision.autoHitlOnDestructive()).isEqualTo(autoHitl);
        assertThat(decision.approvalRequired()).isEqualTo(expected);
    }

    @Test
    void projectEndpointHonoursAclAndRoundTripsTheDecision() {
        Project project = data.project()
                .named("hitl-policy-rest-" + UUID.randomUUID())
                .create();
        project.setAutoHitlOnDestructive(true);
        projectRepository.saveAndFlush(project);

        Tool tool = createTool("t-rest-" + shortId(), RiskClass.DESTRUCTIVE);

        ResponseEntity<HitlPolicyService.Decision> resp = restTemplate.exchange(
                "/api/v1/projects/" + project.getId() + "/hitl-policy?toolCode=" + tool.getCode(),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                HitlPolicyService.Decision.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().approvalRequired()).isTrue();
        assertThat(resp.getBody().riskClass()).isEqualTo(RiskClass.DESTRUCTIVE);
        assertThat(resp.getBody().autoHitlOnDestructive()).isTrue();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Tool createTool(String code, RiskClass riskClass) {
        Tool tool = new Tool();
        tool.setCode(code);
        tool.setName(code);
        tool.setToolType(ToolType.CUSTOM);
        tool.setStatus(ToolStatus.ACTIVE);
        tool.setRiskClass(riskClass);
        return toolRepository.saveAndFlush(tool);
    }
}
