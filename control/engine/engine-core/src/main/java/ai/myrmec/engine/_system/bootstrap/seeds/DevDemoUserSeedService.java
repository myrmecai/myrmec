// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.bootstrap.seeds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.myrmec.engine.team.Team;
import ai.myrmec.engine.team.TeamRepository;
import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Bootstrap component to create a demo user for the dev-only VS Code plugin
 * login page.
 *
 * <p>Only runs when {@code myrmec.dev-login-page.enabled=true}. Creates a LOCAL
 * demo user if one with the configured email does not exist, then grants the
 * user {@code TEAM_OWNER} on the seeded "Fullstack Developer Team" so the
 * plugin can list and use the demo assistant/workflows.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DevDemoUserSeedService {

    private static final String DEMO_TEAM_NAME = "Fullstack Developer Team";

    private final UserService userService;
    private final TeamRepository teamRepository;

    @Value("${myrmec.dev-login-page.enabled:false}")
    private boolean enabled;

    @Value("${myrmec.dev-login-page.demo-email:demo@myrmec.dev}")
    private String demoEmail;

    @Value("${myrmec.dev-login-page.demo-password:Demo123!}")
    private String demoPassword;

    public void onApplicationReady() {
        if (!enabled) {
            log.debug("Dev login page is not enabled; skipping demo user seed");
            return;
        }

        if (demoEmail == null || demoEmail.isBlank()) {
            log.warn("Dev login page enabled but demo-email is blank; skipping demo user seed");
            return;
        }
        if (demoPassword == null || demoPassword.isBlank()) {
            log.warn("Dev login page enabled but demo-password is blank; skipping demo user seed");
            return;
        }

        Team team = teamRepository.findByName(DEMO_TEAM_NAME).orElse(null);
        if (team == null) {
            log.warn("Demo team '{}' not found; skipping demo user seed", DEMO_TEAM_NAME);
            return;
        }

        String normalizedEmail = demoEmail.toLowerCase().trim();
        User demoUser = userService.findByEmailOrNull(normalizedEmail);
        if (demoUser == null) {
            try {
                demoUser = userService.createLocalUser(normalizedEmail, "Demo User", demoPassword, null);
                log.info("Created demo user: {}", normalizedEmail);
            } catch (Exception e) {
                log.error("Failed to create demo user {}: {}", normalizedEmail, e.getMessage(), e);
                throw new IllegalStateException(
                        "Cannot start: failed to create demo user " + normalizedEmail, e);
            }
        } else {
            log.debug("Demo user already exists: {}", normalizedEmail);
        }

        boolean alreadyOwner = userService.getRoles(demoUser.getId()).stream()
                .anyMatch(r -> r.getRole() == UserRole.Role.TEAM_OWNER
                        && r.getScopeType() == UserRole.ScopeType.TEAM
                        && team.getId().equals(r.getTeamId()));
        if (!alreadyOwner) {
            userService.assignRole(demoUser.getId(), UserRole.Role.TEAM_OWNER,
                    UserRole.ScopeType.TEAM, null, team.getId(), null);
            log.info("Granted TEAM_OWNER on '{}' to demo user {}", DEMO_TEAM_NAME, normalizedEmail);
        } else {
            log.debug("Demo user already has TEAM_OWNER on '{}'", DEMO_TEAM_NAME);
        }
    }
}
