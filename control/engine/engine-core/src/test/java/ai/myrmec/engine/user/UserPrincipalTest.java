package ai.myrmec.engine.user;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link UserPrincipal} JWT claim resolution and implicit-role expansion.
 *
 * <p>These tests guard the role contract documented in
 * {@code .github/instructions/myrmec-project.instructions.md}:
 * <ul>
 *   <li>Claim format: {@code sys:ROLE}, {@code grp:&lt;uuid&gt;:ROLE}, {@code proj:&lt;uuid&gt;:ROLE}</li>
 *   <li>Implicit-role expansion: PROJECT_OWNER ⇒ EDITOR ⇒ VIEWER, APPROVER ⇒ VIEWER, AUDITOR ⇒ VIEWER, ORG_ADMIN ⇒ AUDITOR ⇒ VIEWER</li>
 *   <li>PLATFORM_ADMIN does <strong>not</strong> imply data-access (separation of duties).</li>
 * </ul>
 */
class UserPrincipalTest {

    private static final UUID UID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private UserPrincipal principal(String... claims) {
        return new UserPrincipal(UID, "Test User", "test@local", List.of(claims));
    }

    // ---------- Implicit-role expansion ----------

    @Test
    void projectOwnerImpliesEditorAndViewer() {
        UUID p = UUID.randomUUID();
        UserPrincipal up = principal("proj:" + p + ":PROJECT_OWNER");

        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.PROJECT_OWNER));
        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.EDITOR));
        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.VIEWER));
        assertFalse(up.hasProjectRoleDirect(p, UserRole.Role.APPROVER));
    }

    @Test
    void editorImpliesViewer() {
        UUID g = UUID.randomUUID();
        UserPrincipal up = principal("grp:" + g + ":EDITOR");

        assertTrue(up.hasGroupRoleDirect(g, UserRole.Role.EDITOR));
        assertTrue(up.hasGroupRoleDirect(g, UserRole.Role.VIEWER));
        assertFalse(up.hasGroupRoleDirect(g, UserRole.Role.PROJECT_OWNER));
    }

    @Test
    void approverImpliesViewerOnly() {
        UUID p = UUID.randomUUID();
        UserPrincipal up = principal("proj:" + p + ":APPROVER");

        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.APPROVER));
        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.VIEWER));
        assertFalse(up.hasProjectRoleDirect(p, UserRole.Role.EDITOR));
    }

    @Test
    void orgAdminImpliesAuditorAtSystemScope() {
        UserPrincipal up = principal("sys:ORG_ADMIN");

        assertTrue(up.isOrgAdmin());
        assertTrue(up.hasSystemRole(UserRole.Role.AUDITOR));
        // AUDITOR is read-only across data + audit + spend, so the closure also includes VIEWER.
        assertTrue(up.hasSystemRole(UserRole.Role.VIEWER));
        assertFalse(up.hasSystemRole(UserRole.Role.EDITOR));
    }

    @Test
    void auditorImpliesViewer() {
        UserPrincipal up = principal("sys:AUDITOR");

        assertTrue(up.hasSystemRole(UserRole.Role.AUDITOR));
        assertTrue(up.hasSystemRole(UserRole.Role.VIEWER));
        assertFalse(up.hasSystemRole(UserRole.Role.EDITOR));
    }

    @Test
    void platformAdminDoesNotImplyDataAccess() {
        UserPrincipal up = principal("sys:PLATFORM_ADMIN");

        assertTrue(up.isPlatformAdmin());
        assertFalse(up.hasSystemRole(UserRole.Role.EDITOR),
                "PLATFORM_ADMIN must not imply EDITOR — separation of duties");
        assertFalse(up.hasSystemRole(UserRole.Role.VIEWER),
                "PLATFORM_ADMIN must not imply VIEWER — separation of duties");
        assertFalse(up.hasSystemRole(UserRole.Role.AUDITOR));
    }

    // ---------- Scope isolation ----------

    @Test
    void projectRoleDoesNotLeakIntoSystem() {
        UUID p = UUID.randomUUID();
        UserPrincipal up = principal("proj:" + p + ":EDITOR");

        assertFalse(up.hasSystemRole(UserRole.Role.EDITOR));
        assertFalse(up.hasGroupRoleDirect(UUID.randomUUID(), UserRole.Role.EDITOR));
    }

    @Test
    void groupRoleAvailableViaAncestorWalk() {
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UserPrincipal up = principal("grp:" + parent + ":EDITOR");

        assertTrue(up.hasGroupRoleInChain(java.util.Set.of(child, parent), UserRole.Role.EDITOR));
        assertFalse(up.hasGroupRoleDirect(child, UserRole.Role.EDITOR));
    }

    // ---------- Claim parsing edge cases ----------

    @Test
    void unknownPrefixIsIgnored() {
        UserPrincipal up = principal("foo:BAR", "sys:VIEWER");

        assertTrue(up.hasSystemRole(UserRole.Role.VIEWER));
        assertEquals(1, up.getEffectiveSystemRoles().size());
    }

    @Test
    void malformedScopedClaimIsIgnored() {
        UserPrincipal up = principal("proj:not-a-uuid:EDITOR", "grp::EDITOR");

        assertTrue(up.projectIdsWithAnyRole().isEmpty());
        assertTrue(up.groupIdsWithAnyRole().isEmpty());
    }

    @Test
    void unknownRoleNameIsIgnored() {
        UUID p = UUID.randomUUID();
        UserPrincipal up = principal("proj:" + p + ":WIZARD", "proj:" + p + ":EDITOR");

        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.EDITOR));
        // Only EDITOR (+ VIEWER from implicit) — WIZARD ignored.
        assertEquals(2, up.getEffectiveProjectRoles().get(p).size());
    }

    @Test
    void multipleClaimsAreUnioned() {
        UUID p = UUID.randomUUID();
        UserPrincipal up = principal(
                "proj:" + p + ":APPROVER",
                "proj:" + p + ":BUDGET_OWNER");

        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.APPROVER));
        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.BUDGET_OWNER));
        assertTrue(up.hasProjectRoleDirect(p, UserRole.Role.VIEWER)); // from APPROVER
    }
}
