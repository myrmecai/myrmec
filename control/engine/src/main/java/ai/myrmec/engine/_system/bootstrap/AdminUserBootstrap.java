package ai.myrmec.engine._system.bootstrap;

import ai.myrmec.engine.user.User;
import ai.myrmec.engine.user.UserRole;
import ai.myrmec.engine.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bootstrap component to create the initial admin user on startup.
 *
 * <p>If no PLATFORM_ADMIN user exists, creates one using environment variables:
 * <ul>
 *   <li>MYRMEC_ADMIN_EMAIL - Required</li>
 *   <li>MYRMEC_ADMIN_PASSWORD - Required</li>
 *   <li>MYRMEC_ADMIN_NAME - Optional, defaults to "System Administrator"</li>
 * </ul>
 *
 * <p>The bootstrap admin is granted both {@code PLATFORM_ADMIN} (technical
 * platform operations) and {@code ORG_ADMIN} (business governance) so the
 * first run is frictionless. Operators can later split these between
 * separate users.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminUserBootstrap {

    private final UserService userService;

    @Value("${myrmec.admin.email:}")
    private String adminEmail;

    @Value("${myrmec.admin.password:}")
    private String adminPassword;

    @Value("${myrmec.admin.name:System Administrator}")
    private String adminName;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (userService.hasAnyPlatformAdmin()) {
            log.debug("Platform admin already exists, skipping bootstrap");
            return;
        }

        if (adminEmail == null || adminEmail.isBlank()) {
            log.error("No PLATFORM_ADMIN user exists and MYRMEC_ADMIN_EMAIL is not set. " +
                    "Set MYRMEC_ADMIN_EMAIL and MYRMEC_ADMIN_PASSWORD environment variables.");
            throw new IllegalStateException(
                    "Cannot start: No admin user exists and MYRMEC_ADMIN_EMAIL is not configured");
        }

        if (adminPassword == null || adminPassword.isBlank()) {
            log.error("MYRMEC_ADMIN_PASSWORD is not set. Cannot create admin user without password.");
            throw new IllegalStateException(
                    "Cannot start: MYRMEC_ADMIN_PASSWORD is not configured");
        }

        try {
            // Create admin user
            User admin = userService.createLocalUser(
                    adminEmail,
                    adminName,
                    adminPassword,
                    null  // No creator for bootstrap user
            );

            // Mark as system user (cannot be edited or deleted)
            admin.setIsSystem(true);
            userService.save(admin);

            // Assign both PLATFORM_ADMIN (tech ops) and ORG_ADMIN (governance).
            userService.assignSystemRole(admin.getId(), UserRole.Role.PLATFORM_ADMIN, null);
            userService.assignSystemRole(admin.getId(), UserRole.Role.ORG_ADMIN, null);

            log.info("========================================");
            log.info("Created initial PLATFORM_ADMIN + ORG_ADMIN user: {}", adminEmail);
            log.info("========================================");

        } catch (Exception e) {
            log.error("Failed to create initial admin user: {}", e.getMessage());
            throw new IllegalStateException("Cannot start: Failed to create admin user", e);
        }
    }
}
