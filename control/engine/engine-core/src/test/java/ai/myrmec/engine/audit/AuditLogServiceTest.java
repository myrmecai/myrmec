package ai.myrmec.engine.audit;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRepository;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9b — exercises the audit log writer end-to-end on the same
 * H2 DB the rest of the engine uses. Confirms:
 * <ul>
 *   <li>direct {@code record(...)} persistence + payload serialisation,</li>
 *   <li>the role-grant hook in {@code UserService#assignRole} writes
 *       a USER_ROLE_GRANTED row,</li>
 *   <li>{@link AuditLogEntryRepository#search} filters correctly.</li>
 * </ul>
 */
class AuditLogServiceTest extends IntegrationTestBase {

    @Autowired private AuditLogService auditLogService;
    @Autowired private AuditLogEntryRepository auditLogEntryRepository;
    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @Test
    void recordsDirectEventWithSerialisedPayload() {
        UUID actor = TEST_ADMIN_ID;

        Optional<AuditLogEntry> written = auditLogService.record(
                AuditLogService.AuditEvent.builder()
                        .action("UNIT_TEST")
                        .actorUserId(actor)
                        .resourceType("RESOURCE")
                        .payload(java.util.Map.of("foo", "bar", "n", 1))
                        .build());

        assertThat(written).isPresent();
        AuditLogEntry row = written.get();
        assertThat(row.getId()).isNotNull();
        assertThat(row.getAction()).isEqualTo("UNIT_TEST");
        assertThat(row.getActorUserId()).isEqualTo(actor);
        assertThat(row.getResourceType()).isEqualTo("RESOURCE");
        assertThat(row.getPayloadJson()).contains("\"foo\":\"bar\"");
        assertThat(row.getCreatedAt()).isNotNull();
    }

    @Test
    void roleGrantHookWritesUserRoleGrantedRow() {
        // Create a target user via repository (UserService.createLocalUser
        // requires password validation we don't want to wire here).
        User target = new User();
        target.setEmail("audit-target-" + UUID.randomUUID() + "@e2e.local");
        target.setName("audit target");
        target.setProviderCode("LOCAL");
        target.setIsActive(true);
        target = userRepository.save(target);

        userService.assignRole(
                target.getId(),
                UserRole.Role.VIEWER,
                UserRole.ScopeType.PROJECT,
                null,
                UUID.randomUUID(),
                TEST_ADMIN_ID);

        List<AuditLogEntry> rows = auditLogEntryRepository.findAll();
        assertThat(rows).extracting(AuditLogEntry::getAction)
                .contains("USER_ROLE_GRANTED");
        AuditLogEntry granted = rows.stream()
                .filter(e -> "USER_ROLE_GRANTED".equals(e.getAction()))
                .findFirst().orElseThrow();
        assertThat(granted.getActorUserId()).isEqualTo(TEST_ADMIN_ID);
        assertThat(granted.getResourceId()).isEqualTo(target.getId());
        assertThat(granted.getScopeType()).isEqualTo("PROJECT");
        assertThat(granted.getPayloadJson()).contains("VIEWER");
    }

    @Test
    void searchFiltersByActionAndActor() {
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action("ALPHA").actorUserId(TEST_ADMIN_ID).build());
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action("BETA").actorUserId(TEST_ADMIN_ID).build());
        auditLogService.record(AuditLogService.AuditEvent.builder()
                .action("ALPHA").actorUserId(UUID.randomUUID()).build());

        var alphaForAdmin = auditLogEntryRepository.search(
                TEST_ADMIN_ID, "ALPHA", null, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(alphaForAdmin.getContent()).hasSize(1);
        assertThat(alphaForAdmin.getContent().get(0).getAction()).isEqualTo("ALPHA");
        assertThat(alphaForAdmin.getContent().get(0).getActorUserId()).isEqualTo(TEST_ADMIN_ID);

        var allForAdmin = auditLogEntryRepository.search(
                TEST_ADMIN_ID, null, null, null, null, null,
                org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(allForAdmin.getContent()).hasSize(2);
    }
}
