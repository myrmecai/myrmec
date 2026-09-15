// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Capacity-based host selection (decoupling §3.7).
 *
 * <p>These tests are discriminating: the outcome must depend on scope and
 * liveness, never on a profile binding. Every host in the fixture is
 * created with the <em>same</em> arbitrary profile so that a profile-based
 * selector would be indeterminate, while the capacity-based selector
 * returns the project-scoped / live host.
 */
class HostSelectionServiceTest extends IntegrationTestBase {

    @Autowired
    TestDataBuilder data;

    @Autowired
    AgentHostInstanceRepository instanceRepository;

    @Autowired
    HostSelectionService selector;

    @Test
    void selectsProjectScopedHostOverUnscopedWhenBothLive() {
        AgentProfile profile = data.agentProfile().named("selector-profile").create();
        Project project = data.project().named("selector-project").create();

        AgentHost scoped = data.agent().named("scoped-live").inProject(project).create().agent();
        AgentHost unscoped = data.agent().named("unscoped-live").create().agent();

        openInstance(scoped);
        openInstance(unscoped);

        assertThat(selector.selectForProject(project.getId()))
                .isPresent()
                .hasValueSatisfying(h -> assertThat(h.getId()).isEqualTo(scoped.getId()));
    }

    @Test
    void ignoresHostWithoutLiveInstance() {
        AgentProfile profile = data.agentProfile().named("selector-profile").create();
        Project project = data.project().named("selector-project").create();

        data.agent().named("dead-host").inProject(project).create();

        assertThat(selector.selectForProject(project.getId())).isEmpty();
    }

    @Test
    void prefersUnscopedLiveHostOverScopedDeadHost() {
        AgentProfile profile = data.agentProfile().named("selector-profile").create();
        Project project = data.project().named("selector-project").create();

        AgentHost scopedDead = data.agent().named("scoped-dead").inProject(project).create().agent();
        AgentHost unscopedLive = data.agent().named("unscoped-live").create().agent();

        openInstance(unscopedLive);

        assertThat(selector.selectForProject(project.getId()))
                .isPresent()
                .hasValueSatisfying(h -> assertThat(h.getId()).isEqualTo(unscopedLive.getId()));
    }

    @Test
    void emptyWhenNoHostIsActive() {
        Project project = data.project().named("selector-project").create();

        assertThat(selector.selectForProject(project.getId())).isEmpty();
    }

    @Test
    void selectForNullPrefersUnscopedLiveHost() {
        AgentProfile profile = data.agentProfile().named("selector-profile").create();

        AgentHost scoped = data.agent().named("scoped-live").inProject(data.project().named("other-project").create()).create().agent();
        AgentHost unscoped = data.agent().named("unscoped-live").create().agent();

        openInstance(scoped);
        openInstance(unscoped);

        assertThat(selector.selectForProject(null))
                .isPresent()
                .hasValueSatisfying(h -> assertThat(h.getId()).isEqualTo(unscoped.getId()));
    }

    private void openInstance(AgentHost host) {
        instanceRepository.saveAndFlush(AgentHostInstance.open(
                host, null, "dev-laptop", 1, Map.of("cpuCount", 2), "engine-node-1"));
    }
}
