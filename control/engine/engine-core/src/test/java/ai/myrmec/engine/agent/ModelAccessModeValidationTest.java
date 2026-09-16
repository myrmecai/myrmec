// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.agent;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.agent.dto.AgentResponse;
import ai.myrmec.engine.inference.Session;
import ai.myrmec.engine.inference.SessionRepository;
import ai.myrmec.engine.inference.SessionContextAssembler;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.setting.SystemSettingService;
import ai.myrmec.engine.testing.TestDataBuilder;
import ai.myrmec.engine.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Credential-envelope design §5.2/§5.3: the model access mode validation
 * matrix and the governance mandate.
 *
 * <ul>
 *   <li>§(a) gateway-off + DIRECT host → allowed (dev-phase default)</li>
 *   <li>§(b) gateway-off + GATEWAY host → rejected at create (mode unbacked)</li>
 *   <li>§(c) gateway-on: MANAGED+DIRECT, MANAGED+GATEWAY, LOCAL+GATEWAY OK</li>
 *   <li>§(d) governance mandate (STRICT) rejects LOCAL+DIRECT with
 *       GOVERNANCE_VIOLATION; mandate OFF (STANDARD) allows it</li>
 *   <li>§(e) mode changes are PLATFORM_ADMIN only (method-level guard +
 *       runtime guard on the shared PUT)</li>
 *   <li>§(f) dispatch-time stub: a GATEWAY-mode host fails session assembly
 *       (no silent Direct fallback)</li>
 * </ul>
 */
@DisplayName("Model access mode: validation matrix, mandate, admin guard, dispatch stub")
class ModelAccessModeValidationTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private AgentHostService agentHostService;

    @Autowired
    private AgentHostRepository agentHostRepository;

    @Autowired
    private ModelAccessModeValidator validator;

    @Autowired
    private SystemSettingService systemSettingService;

    @Autowired
    private SessionContextAssembler sessionContextAssembler;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private ai.myrmec.engine.agent.AgentHostInstanceRepository agentHostInstanceRepository;

    @Autowired
    private ai.myrmec.engine.agent.AgentProfileRepository agentProfileRepository;

    @Autowired
    private ai.myrmec.engine.governance.GovernanceProfileService governanceProfileService;

    @Autowired
    private ai.myrmec.engine.user.UserRepository userRepository;

    // ── (a) gateway off + DIRECT: the dev-phase default ──────────────

    @Test
    @DisplayName("gateway off: DIRECT host is allowed (dev-phase default)")
    void directAllowedWithGatewayOff() {
        AgentHost host = data.agent().named("mode-direct-default").create().agent();
        assertThat(host.getModelAccessMode()).isEqualTo(ModelAccessMode.DIRECT);

        // Explicit DIRECT through the validator passes without exception.
        validator.validate(AgentHostType.MANAGED, ModelAccessMode.DIRECT);
    }

    // ── (b) gateway off + GATEWAY: rejected at create ────────────────

    @Test
    @DisplayName("gateway off: GATEWAY host is rejected at create (mode unbacked)")
    void gatewayRejectedWhenGatewayOff() {
        assertThatThrownBy(() -> data.agent().named("mode-gw")
                .withModelAccessMode(ModelAccessMode.GATEWAY).create().agent())
                .isInstanceOf(ai.myrmec.engine._system.exception.BadRequestException.class)
                .satisfies(ex -> {
                    var bre = (ai.myrmec.engine._system.exception.BadRequestException) ex;
                    assertThat(bre.getDetails()).anySatisfy(d -> {
                        assertThat(d.getField()).isEqualTo("modelAccessMode");
                        assertThat(d.getErrorCode()).isEqualTo("INVALID_VALUE");
                    });
                });

        assertThatThrownBy(() -> validator.validate(AgentHostType.LOCAL, ModelAccessMode.GATEWAY))
                .isInstanceOf(ai.myrmec.engine._system.exception.BadRequestException.class)
                .satisfies(ex -> {
                    var bre = (ai.myrmec.engine._system.exception.BadRequestException) ex;
                    assertThat(bre.getDetails()).anySatisfy(d ->
                            assertThat(d.getMessage()).contains("model gateway"));
                });
    }

    // ── (c) gateway on: the allowed matrix rows ──────────────────────

    @Test
    @DisplayName("gateway on: MANAGED+DIRECT, MANAGED+GATEWAY and LOCAL+GATEWAY are allowed")
    void gatewayOnMatrixAllows() {
        withGatewayEnabled(true, () -> {
            validator.validate(AgentHostType.MANAGED, ModelAccessMode.DIRECT);
            validator.validate(AgentHostType.MANAGED, ModelAccessMode.GATEWAY);
            validator.validate(AgentHostType.LOCAL, ModelAccessMode.GATEWAY);

            // End-to-end create with an explicit GATEWAY mode.
            AgentHost host = data.agent().named("mode-gw-on")
                    .withModelAccessMode(ModelAccessMode.GATEWAY).create().agent();
            assertThat(host.getModelAccessMode()).isEqualTo(ModelAccessMode.GATEWAY);
        });
    }

    // ── (d) governance mandate ───────────────────────────────────────

    @Test
    @DisplayName("mandate ON (STRICT): LOCAL+DIRECT rejected with GOVERNANCE_VIOLATION; OFF (STANDARD) allowed")
    void governanceMandateRejectsLocalDirect() {
        // Standard (mandate OFF): LOCAL+DIRECT is allowed pre-Phase-2.
        governanceProfileService.setDefaultProfile("STANDARD", TEST_ADMIN_ID);
        try {
            withGatewayEnabled(true, () ->
                    validator.validate(AgentHostType.LOCAL, ModelAccessMode.DIRECT));
        } finally {
            governanceProfileService.setDefaultProfile("STANDARD", TEST_ADMIN_ID);
        }

        // Strict (mandate ON): LOCAL+DIRECT is a GOVERNANCE_VIOLATION.
        governanceProfileService.setDefaultProfile("STRICT", TEST_ADMIN_ID);
        try {
            assertThatThrownBy(() ->
                    validator.validate(AgentHostType.LOCAL, ModelAccessMode.DIRECT))
                    .isInstanceOf(ai.myrmec.engine.governance.GovernanceViolationException.class)
                    .satisfies(ex -> {
                        var gve = (ai.myrmec.engine.governance.GovernanceViolationException) ex;
                        assertThat(gve.getFeature())
                                .isEqualTo(ai.myrmec.engine.governance.ProductFeature.LOCAL_HOST_MODEL_GATEWAY);
                        assertThat(gve.getProfileCode()).isEqualTo("STRICT");
                    });
            // The mandate does not touch MANAGED hosts or GATEWAY mode.
            validator.validate(AgentHostType.MANAGED, ModelAccessMode.DIRECT);
            withGatewayEnabled(true, () ->
                    validator.validate(AgentHostType.LOCAL, ModelAccessMode.GATEWAY));
        } finally {
            governanceProfileService.setDefaultProfile("STANDARD", TEST_ADMIN_ID);
        }
    }

    // ── (e) mode changes are PLATFORM_ADMIN only ─────────────────────

    @Test
    @DisplayName("mode change via PUT /model-access-mode requires PLATFORM_ADMIN (editor denied)")
    void modeChangeRequiresPlatformAdmin() {
        withGatewayEnabled(true, () -> {
            AgentHost host = data.agent().named("mode-admin-host")
                    .withModelAccessMode(ModelAccessMode.DIRECT).create().agent();
            Project project = data.project().named("mode-admin-prj").create();

            // An EDITOR-scoped user may not change the mode.
            HttpHeaders editor = userHeaders(createUserRow(), project.getId());
            ResponseEntity<String> editorResp = restTemplate.exchange(
                    "/api/v1/admin/agent-hosts/" + host.getId() + "/model-access-mode",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("modelAccessMode", "GATEWAY"), editor),
                    String.class);
            assertThat(editorResp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            // An unauthenticated caller is rejected too.
            ResponseEntity<String> anon = restTemplate.exchange(
                    "/api/v1/admin/agent-hosts/" + host.getId() + "/model-access-mode",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("modelAccessMode", "GATEWAY")),
                    String.class);
            assertThat(anon.getStatusCode().value()).isIn(401, 403);

            // A platform admin can change the mode.
            ResponseEntity<AgentResponse> adminResp = restTemplate.exchange(
                    "/api/v1/admin/agent-hosts/" + host.getId() + "/model-access-mode",
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("modelAccessMode", "GATEWAY"), adminHeaders()),
                    AgentResponse.class);
            assertThat(adminResp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(adminResp.getBody().getModelAccessMode()).isEqualTo(ModelAccessMode.GATEWAY);
        });
    }

    @Test
    @DisplayName("EDITORs may update other host fields but not the mode field on the shared PUT")
    void sharedPutRejectsEditorModeChange() {
        withGatewayEnabled(true, () -> {
            AgentHost host = data.agent().named("mode-shared-host").create().agent();
            Project project = data.project().named("mode-shared-prj").create();
            HttpHeaders editor = userHeaders(createUserRow(), project.getId());

            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/admin/agent-hosts/" + host.getId(),
                    HttpMethod.PUT,
                    new HttpEntity<>(Map.of("modelAccessMode", "GATEWAY"), editor),
                    String.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

            AgentHost reloaded = agentHostRepository.findById(host.getId()).orElseThrow();
            assertThat(reloaded.getModelAccessMode()).isEqualTo(ModelAccessMode.DIRECT);
        });
    }

    // ── (f) dispatch-time stub: GATEWAY mode fails loudly ────────────

    @Test
    @DisplayName("GATEWAY-mode host: session assembly fails instead of silently using Direct")
    void gatewayModeHostFailsDispatch() {
        withGatewayEnabled(true, () -> {
            AgentHost host = data.agent().named("mode-dispatch-host")
                    .withModelAccessMode(ModelAccessMode.GATEWAY).create().agent();
            var instance = agentHostInstanceRepository.saveAndFlush(AgentHostInstance.open(
                    host, null, "laptop", 1, Map.of(), "engine-node-1"));

            Session session = new Session();
            session.setServiceType("CONVERSATION");
            session.setRefId(UUID.randomUUID());
            session.setProjectId(data.project().named("mode-dispatch-prj").create().getId());
            session.setKind("CONVERSATION");
            session.setHostInstanceId(instance.getId());
            session.setAllocationState("INITIALIZING");
            session.setOfferExpiresAt(Instant.now().plusSeconds(10));
            session = sessionRepository.saveAndFlush(session);
            Session pinned = session;

            var profile = data.agentProfile().named("mode-dispatch-profile").create();

            assertThatThrownBy(() -> sessionContextAssembler.assembleContext(
                    pinned.getId(), pinned.getServiceType(), pinned.getRefId(),
                    pinned.getProjectId(), profile.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("GATEWAY");
        });
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void withGatewayEnabled(boolean enabled, Runnable body) {
        String previous = systemSettingService
                .find(ModelGatewaySettings.ENABLED_KEY)
                .map(s -> s.getValue())
                .orElse(null);
        systemSettingService.update(ModelGatewaySettings.ENABLED_KEY, String.valueOf(enabled), TEST_ADMIN_ID);
        try {
            body.run();
        } finally {
            systemSettingService.update(ModelGatewaySettings.ENABLED_KEY,
                    previous == null || previous.isBlank() ? "false" : previous, TEST_ADMIN_ID);
        }
    }

    /** Insert a non-admin user row so the JWT filter authenticates the token. */
    private UUID createUserRow() {
        User user = new User();
        user.setEmail("mode-" + UUID.randomUUID() + "@test.local");
        user.setName("Mode Editor");
        user.setPasswordHash("$2a$10$dummy");
        user.setProviderCode(ai.myrmec.engine.user.AuthenticationProvider.LOCAL_CODE);
        user.setIsActive(true);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.save(user).getId();
    }
}