// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RECON-05: Capability match contract for Agent Profile / Host.
 *
 * <p>Verifies that {@link AgentHost#provisions} (advertised via
 * {@code host.announce}) and {@link AgentProfile#capabilities} (declared
 * requirements) are stored and accessible.
 *
 * <p>Known gap: runtime capability matching (profile.capabilities ⊆ host.provisions)
 * is NOT implemented — host selection is by {@code profileId} + status only.
 * The {@code provisions} field is stored but never read for matching. This
 * contract test verifies the infrastructure exists; matching is a V2 feature.
 */
@Tag("RECON-05")
@Tag("SG5")
@DisplayName("RECON-05: Capability Match Contract")
class CapabilityMatchContractTest extends IntegrationTestBase {

    @Autowired
    private AgentHostRepository agentHostRepository;

    @Autowired
    private AgentProfileRepository agentProfileRepository;

    @Test
    @DisplayName("AgentProfile.capabilities field is stored and readable")
    void profileCapabilitiesStored() {
        // Create a profile with capabilities
        AgentProfile profile = new AgentProfile();
        profile.setName("recon05-profile-" + System.nanoTime());
        profile.setCapabilities(List.of("python:>=3.11", "docker"));
        profile.setStatus(AgentProfile.Status.ACTIVE);
        agentProfileRepository.save(profile);

        var found = agentProfileRepository.findById(profile.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getCapabilities())
                .containsExactly("python:>=3.11", "docker");
    }

    @Test
    @DisplayName("AgentHost.provisions field is stored and readable")
    void hostProvisionsStored() {
        // Create a profile first (AgentHost requires a non-null profileId)
        AgentProfile profile = new AgentProfile();
        profile.setName("recon05-host-profile-" + System.nanoTime());
        profile.setStatus(AgentProfile.Status.ACTIVE);
        agentProfileRepository.save(profile);

        AgentHost host = new AgentHost();
        host.setName("recon05-host-" + System.nanoTime());
        host.setProfileId(profile.getId());
        host.setStatus(AgentHost.Status.ACTIVE);
        host.setRegistrationKey("recon05-regkey-" + System.nanoTime());
        host.setMaxAgents(1);
        host.setProvisions(Map.of("tools", List.of("shell", "python"), "runtime", List.of("python:3.11")));
        agentHostRepository.save(host);

        var found = agentHostRepository.findById(host.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getProvisions()).containsKey("tools");
    }
}