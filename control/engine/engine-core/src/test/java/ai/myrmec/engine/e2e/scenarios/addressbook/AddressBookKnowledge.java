package ai.myrmec.engine.e2e.scenarios.addressbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads knowledge document content from test resources for the Address Book scenario.
 */
public final class AddressBookKnowledge {

    private static final String BASE_PATH = "e2e/addressbook/";

    private AddressBookKnowledge() {
    }

    // ==================== Org-Level Documents ====================

    public static String codingStandards() {
        return loadResource("org-level/coding-standards.md");
    }

    public static String securityGuidelines() {
        return loadResource("org-level/security-guidelines.md");
    }

    // ==================== Project-Level Documents ====================

    public static String javaSpringStandards() {
        return loadResource("project-level/java-spring-standards.md");
    }

    public static String reactTypeScriptStandards() {
        return loadResource("project-level/react-typescript-standards.md");
    }

    public static String restApiConventions() {
        return loadResource("project-level/rest-api-conventions.md");
    }

    public static String architecture() {
        return loadResource("project-level/architecture.md");
    }

    // ==================== Helper ====================

    private static String loadResource(String relativePath) {
        String fullPath = BASE_PATH + relativePath;
        try (InputStream is = AddressBookKnowledge.class.getClassLoader().getResourceAsStream(fullPath)) {
            if (is == null) {
                throw new IllegalStateException("Resource not found: " + fullPath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load resource: " + fullPath, e);
        }
    }
}
