// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature 10 (design §16.1/§17.2): the session-credential PSK lifecycle.
 * At Host creation the engine generates a 32-byte PSK + keyId, returns it
 * ONCE in the creation response, and persists only the
 * EncryptionService-encrypted copy — the plaintext never exists in the
 * database.
 */
@DisplayName("F10: Host session-credential PSK (§16.1/§17.2)")
class AgentHostPskTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AgentHostService agentHostService;

    @Autowired
    private AgentHostRepository agentHostRepository;

    @Autowired
    private ai.myrmec.engine.spi.crypto.EncryptionService encryptionService;

    @Test
    @DisplayName("creation returns the PSK once; storage holds only the encrypted copy")
    void creationReturnsPskOnceStoresEncrypted() {
        UUID profileId = data.agentProfile().named("psk-profile").create().getId();

        AgentHostCreationResult created = agentHostService.createAgent(
                "psk-host-" + System.nanoTime(), "PSK test host",
                profileId, null, null, null, 1);

        // The response carries the one-time PSK material.
        assertThat(created.pskKeyId()).startsWith("psk-");
        byte[] psk = Base64.getDecoder().decode(created.pskBase64());
        assertThat(psk).hasSize(32);

        // The stored row holds keyId + at-rest-encrypted bytes — decryptable
        // back to the SAME PSK (engine-side transient use only), and the raw
        // key bytes never appear in the stored form.
        AgentHost stored = agentHostRepository.findById(created.agent().getId()).orElseThrow();
        assertThat(stored.getPskKeyId()).isEqualTo(created.pskKeyId());
        assertThat(stored.getPskEncrypted()).isNotNull();
        assertThat(new String(stored.getPskEncrypted()))
                .doesNotContain(created.pskBase64());
        String decrypted = encryptionService.decrypt(stored.getPskEncrypted());
        assertThat(decrypted).isEqualTo(created.pskBase64());

        // Two hosts never share a PSK.
        AgentHostCreationResult other = agentHostService.createAgent(
                "psk-host2-" + System.nanoTime(), "Second host",
                profileId, null, null, null, 1);
        assertThat(other.pskKeyId()).isNotEqualTo(created.pskKeyId());
        assertThat(other.pskBase64()).isNotEqualTo(created.pskBase64());
    }
}