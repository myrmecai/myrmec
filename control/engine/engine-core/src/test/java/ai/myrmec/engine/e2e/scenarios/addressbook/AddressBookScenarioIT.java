package ai.myrmec.engine.e2e.scenarios.addressbook;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.TestDataFactory;
import ai.myrmec.engine.knowledge.KnowledgeCategory;
import ai.myrmec.engine.knowledge.KnowledgeDocument;
import ai.myrmec.engine.knowledge.TaskContextResolver;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.websocket.message.payload.TaskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the Address Book application scenario.
 * 
 * <p>Tests verify that agents receive correct knowledge context based on:
 * <ul>
 *   <li>File type being generated (Java vs TypeScript/React)</li>
 *   <li>Specific file patterns (controller, API client)</li>
 *   <li>Priority ordering (highest first)</li>
 *   <li>Org + project combination</li>
 * </ul>
 * 
 * <p>Simulated project structure:
 * <pre>
 * address-book/
 * ├── backend/                    # Java 21 + Spring Boot 3 + Maven
 * │   └── src/main/java/com/example/addressbook/
 * │       ├── contact/Contact.java, ContactController.java
 * │       └── group/Group.java
 * └── frontend/                   # React 18 + TypeScript + Vite
 *     └── src/
 *         ├── components/ContactList.tsx, ContactCard.tsx
 *         └── api/contacts.ts
 * </pre>
 */
@DisplayName("Address Book E2E Scenario")
class AddressBookScenarioIT extends IntegrationTestBase {

    @Autowired
    private TaskContextResolver taskContextResolver;

    private Project addressBookProject;

    // Knowledge document names for assertions
    private static final String CODING_STANDARDS = "Company Coding Standards";
    private static final String SECURITY_GUIDELINES = "Security Guidelines";
    private static final String JAVA_SPRING_STANDARDS = "Java Spring Boot Standards";
    private static final String REACT_TS_STANDARDS = "React TypeScript Standards";
    private static final String REST_API_CONVENTIONS = "REST API Conventions";
    private static final String ARCHITECTURE = "Address Book Architecture";

    @BeforeEach
    void setupAddressBookScenario() {
        // 1. Create project
        addressBookProject = TestDataFactory.projectBuilder("address-book-test")
                .description("Address Book application for E2E testing")
                .build();
        addressBookProject = projectRepository.save(addressBookProject);

        // 2. Create organization-level knowledge documents
        createOrgKnowledge();

        // 3. Create project-level knowledge documents
        createProjectKnowledge();
    }

    private void createOrgKnowledge() {
        // Coding Standards - applies to all files (priority 100)
        KnowledgeDocument codingStandards = TestDataFactory.knowledgeDocument(CODING_STANDARDS)
                .category(KnowledgeCategory.STANDARD)
                .organizationScope()
                .content(AddressBookKnowledge.codingStandards())
                .priority(100)
                .createdBy(TEST_ADMIN_ID)
                .build();
        knowledgeDocumentRepository.save(codingStandards);

        // Security Guidelines - applies to all files (priority 150)
        KnowledgeDocument securityGuidelines = TestDataFactory.knowledgeDocument(SECURITY_GUIDELINES)
                .category(KnowledgeCategory.REQUIREMENT)
                .organizationScope()
                .content(AddressBookKnowledge.securityGuidelines())
                .priority(150)
                .createdBy(TEST_ADMIN_ID)
                .build();
        knowledgeDocumentRepository.save(securityGuidelines);
    }

    private void createProjectKnowledge() {
        UUID projectId = addressBookProject.getId();

        // Java Spring Boot Standards - applies to *.java (priority 200)
        KnowledgeDocument javaStandards = TestDataFactory.knowledgeDocument(JAVA_SPRING_STANDARDS)
                .category(KnowledgeCategory.INSTRUCTION)
                .projectScope(projectId)
                .content(AddressBookKnowledge.javaSpringStandards())
                .priority(200)
                .appliesTo("**/*.java")
                .createdBy(TEST_ADMIN_ID)
                .build();
        knowledgeDocumentRepository.save(javaStandards);

        // React TypeScript Standards - applies to *.tsx, *.ts (priority 200)
        KnowledgeDocument reactStandards = TestDataFactory.knowledgeDocument(REACT_TS_STANDARDS)
                .category(KnowledgeCategory.INSTRUCTION)
                .projectScope(projectId)
                .content(AddressBookKnowledge.reactTypeScriptStandards())
                .priority(200)
                .appliesTo("**/*.tsx", "**/*.ts")
                .createdBy(TEST_ADMIN_ID)
                .build();
        knowledgeDocumentRepository.save(reactStandards);

        // REST API Conventions - applies to controllers and API clients (priority 180)
        KnowledgeDocument restApiConventions = TestDataFactory.knowledgeDocument(REST_API_CONVENTIONS)
                .category(KnowledgeCategory.INSTRUCTION)
                .projectScope(projectId)
                .content(AddressBookKnowledge.restApiConventions())
                .priority(180)
                .appliesTo("**/controller/*.java", "**/*Controller.java", "**/api/*.ts")
                .createdBy(TEST_ADMIN_ID)
                .build();
        knowledgeDocumentRepository.save(restApiConventions);

        // Architecture - applies to all files (priority 250)
        KnowledgeDocument architecture = TestDataFactory.knowledgeDocument(ARCHITECTURE)
                .category(KnowledgeCategory.ARCHITECTURE)
                .projectScope(projectId)
                .content(AddressBookKnowledge.architecture())
                .priority(250)
                .createdBy(TEST_ADMIN_ID)
                .build();
        knowledgeDocumentRepository.save(architecture);
    }

    // ==================== Backend Code Generation Tests ====================

    @Nested
    @DisplayName("Backend (Java) Code Generation")
    class BackendCodeGeneration {

        @Test
        @DisplayName("Generating Contact.java should include Java + Architecture docs, not React")
        void generateContactEntity() {
            // When: Resolving context for a Java entity file
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "backend/src/main/java/com/example/addressbook/contact/Contact.java"
            );

            // Then: Should include Java-specific and universal docs
            assertThat(context.getKnowledge()).isNotEmpty();
            assertDocumentIncluded(context, ARCHITECTURE);         // Universal, priority 250
            assertDocumentIncluded(context, JAVA_SPRING_STANDARDS); // **/*.java, priority 200
            assertDocumentIncluded(context, SECURITY_GUIDELINES);   // Universal, priority 150
            assertDocumentIncluded(context, CODING_STANDARDS);      // Universal, priority 100

            // Should NOT include React standards (wrong file type)
            assertDocumentNotIncluded(context, REACT_TS_STANDARDS);
            
            // Should NOT include REST API conventions (entity, not controller)
            assertDocumentNotIncluded(context, REST_API_CONVENTIONS);

            // Verify priority ordering (highest first)
            assertPriorityOrder(context, ARCHITECTURE, JAVA_SPRING_STANDARDS, SECURITY_GUIDELINES, CODING_STANDARDS);
        }

        @Test
        @DisplayName("Generating ContactController.java should include Java + REST API docs")
        void generateContactController() {
            // When: Resolving context for a Java controller file
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "backend/src/main/java/com/example/addressbook/contact/ContactController.java"
            );

            // Then: Should include Java, REST API, and universal docs
            assertDocumentIncluded(context, ARCHITECTURE);          // Universal
            assertDocumentIncluded(context, JAVA_SPRING_STANDARDS);  // **/*.java
            assertDocumentIncluded(context, REST_API_CONVENTIONS);   // **/Controller.java
            assertDocumentIncluded(context, SECURITY_GUIDELINES);    // Universal
            assertDocumentIncluded(context, CODING_STANDARDS);       // Universal

            // Should NOT include React standards
            assertDocumentNotIncluded(context, REACT_TS_STANDARDS);

            // Verify priority ordering
            assertPriorityOrder(context, ARCHITECTURE, JAVA_SPRING_STANDARDS, REST_API_CONVENTIONS, SECURITY_GUIDELINES, CODING_STANDARDS);
        }

        @Test
        @DisplayName("Generating Group.java should include only Java docs (not REST API)")
        void generateGroupEntity() {
            // When: Resolving context for a Java entity (not a controller)
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "backend/src/main/java/com/example/addressbook/group/Group.java"
            );

            // Then: Should include Java-specific and universal docs
            assertDocumentIncluded(context, ARCHITECTURE);
            assertDocumentIncluded(context, JAVA_SPRING_STANDARDS);
            assertDocumentIncluded(context, SECURITY_GUIDELINES);
            assertDocumentIncluded(context, CODING_STANDARDS);

            // Should NOT include REST API conventions (entity, not controller)
            assertDocumentNotIncluded(context, REST_API_CONVENTIONS);
            
            // Should NOT include React standards
            assertDocumentNotIncluded(context, REACT_TS_STANDARDS);
        }
    }

    // ==================== Frontend Code Generation Tests ====================

    @Nested
    @DisplayName("Frontend (React/TypeScript) Code Generation")
    class FrontendCodeGeneration {

        @Test
        @DisplayName("Generating ContactList.tsx should include React + Architecture docs, not Java")
        void generateContactListComponent() {
            // When: Resolving context for a React component
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "frontend/src/components/ContactList.tsx"
            );

            // Then: Should include React-specific and universal docs
            assertDocumentIncluded(context, ARCHITECTURE);         // Universal
            assertDocumentIncluded(context, REACT_TS_STANDARDS);    // **/*.tsx
            assertDocumentIncluded(context, SECURITY_GUIDELINES);   // Universal
            assertDocumentIncluded(context, CODING_STANDARDS);      // Universal

            // Should NOT include Java standards
            assertDocumentNotIncluded(context, JAVA_SPRING_STANDARDS);
            
            // Should NOT include REST API conventions (component, not API client)
            assertDocumentNotIncluded(context, REST_API_CONVENTIONS);

            // Verify priority ordering
            assertPriorityOrder(context, ARCHITECTURE, REACT_TS_STANDARDS, SECURITY_GUIDELINES, CODING_STANDARDS);
        }

        @Test
        @DisplayName("Generating contacts.ts (API client) should include React + REST API docs")
        void generateApiClient() {
            // When: Resolving context for an API client file
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "frontend/src/api/contacts.ts"
            );

            // Then: Should include React, REST API, and universal docs
            assertDocumentIncluded(context, ARCHITECTURE);         // Universal
            assertDocumentIncluded(context, REACT_TS_STANDARDS);    // **/*.ts
            assertDocumentIncluded(context, REST_API_CONVENTIONS);  // **/api/*.ts
            assertDocumentIncluded(context, SECURITY_GUIDELINES);   // Universal
            assertDocumentIncluded(context, CODING_STANDARDS);      // Universal

            // Should NOT include Java standards
            assertDocumentNotIncluded(context, JAVA_SPRING_STANDARDS);

            // Verify priority ordering
            assertPriorityOrder(context, ARCHITECTURE, REACT_TS_STANDARDS, REST_API_CONVENTIONS, SECURITY_GUIDELINES, CODING_STANDARDS);
        }

        @Test
        @DisplayName("Generating useContacts.ts hook should include only React docs (not REST API)")
        void generateHook() {
            // When: Resolving context for a React hook
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "frontend/src/hooks/useContacts.ts"
            );

            // Then: Should include React-specific and universal docs
            assertDocumentIncluded(context, ARCHITECTURE);
            assertDocumentIncluded(context, REACT_TS_STANDARDS);
            assertDocumentIncluded(context, SECURITY_GUIDELINES);
            assertDocumentIncluded(context, CODING_STANDARDS);

            // Should NOT include REST API conventions (hook, not API client)
            assertDocumentNotIncluded(context, REST_API_CONVENTIONS);
            
            // Should NOT include Java standards
            assertDocumentNotIncluded(context, JAVA_SPRING_STANDARDS);
        }
    }

    // ==================== No File Path Tests ====================

    @Nested
    @DisplayName("No File Path (Universal Context)")
    class UniversalContext {

        @Test
        @DisplayName("Resolving without file path should include only universal docs")
        void resolveWithoutFilePath() {
            // When: Resolving context without a specific file path
            TaskContext context = taskContextResolver.resolve(addressBookProject.getId());

            // Then: Should include only universal (no appliesTo) docs
            assertDocumentIncluded(context, ARCHITECTURE);       // No appliesTo
            assertDocumentIncluded(context, SECURITY_GUIDELINES); // No appliesTo
            assertDocumentIncluded(context, CODING_STANDARDS);    // No appliesTo

            // Should NOT include file-specific docs
            assertDocumentNotIncluded(context, JAVA_SPRING_STANDARDS);  // Has appliesTo
            assertDocumentNotIncluded(context, REACT_TS_STANDARDS);     // Has appliesTo
            assertDocumentNotIncluded(context, REST_API_CONVENTIONS);   // Has appliesTo
        }
    }

    // ==================== RAG Config Tests ====================

    @Nested
    @DisplayName("RAG Configuration")
    class RagConfiguration {

        @Test
        @DisplayName("Project with RAG config should include it in context")
        void projectWithRagConfig() {
            // Given: Project with RAG config
            addressBookProject.setRagConfig(Map.of(
                    "endpoint", "https://rag.example.com/api",
                    "api_key_secret", "rag-api-key-secret",
                    "collection", "addressbook-docs",
                    "top_k", 5
            ));
            projectRepository.save(addressBookProject);

            // When: Resolving context
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "backend/src/main/java/com/example/addressbook/contact/Contact.java"
            );

            // Then: RAG config should be present
            assertThat(context.getRag()).isNotNull();
            assertThat(context.getRag().getEndpoint()).isEqualTo("https://rag.example.com/api");
            assertThat(context.getRag().getApiKeySecret()).isEqualTo("rag-api-key-secret");
            assertThat(context.getRag().getCollection()).isEqualTo("addressbook-docs");
            assertThat(context.getRag().getTopK()).isEqualTo(5);
        }

        @Test
        @DisplayName("Project without RAG config should have null RAG in context")
        void projectWithoutRagConfig() {
            // When: Resolving context for project without RAG config
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "backend/src/main/java/com/example/addressbook/contact/Contact.java"
            );

            // Then: RAG config should be null
            assertThat(context.getRag()).isNull();
        }
    }

    // ==================== Character Budget Tests ====================

    @Nested
    @DisplayName("Character Budget")
    class CharacterBudget {

        @Test
        @DisplayName("Context should track knowledge character count")
        void tracksCharacterCount() {
            // When: Resolving context
            TaskContext context = taskContextResolver.resolve(
                    addressBookProject.getId(),
                    "backend/src/main/java/com/example/addressbook/contact/Contact.java"
            );

            // Then: Character count should be positive and reasonable
            assertThat(context.getKnowledgeCharCount()).isGreaterThan(0);
            
            // Calculate expected minimum (sum of all included doc content)
            int minimumExpected = AddressBookKnowledge.architecture().length()
                    + AddressBookKnowledge.javaSpringStandards().length()
                    + AddressBookKnowledge.securityGuidelines().length()
                    + AddressBookKnowledge.codingStandards().length();
            
            assertThat(context.getKnowledgeCharCount()).isGreaterThanOrEqualTo(minimumExpected);
        }
    }

    // ==================== Assertion Helpers ====================

    private void assertDocumentIncluded(TaskContext context, String documentName) {
        boolean found = context.getKnowledge().stream()
                .anyMatch(entry -> documentName.equals(entry.getName()));
        assertThat(found)
                .as("Expected document '%s' to be included in context", documentName)
                .isTrue();
    }

    private void assertDocumentNotIncluded(TaskContext context, String documentName) {
        boolean found = context.getKnowledge().stream()
                .anyMatch(entry -> documentName.equals(entry.getName()));
        assertThat(found)
                .as("Expected document '%s' NOT to be included in context", documentName)
                .isFalse();
    }

    private void assertPriorityOrder(TaskContext context, String... expectedOrder) {
        List<String> actualOrder = context.getKnowledge().stream()
                .map(TaskContext.KnowledgeEntry::getName)
                .toList();

        // Verify expected documents appear in the correct relative order
        int lastIndex = -1;
        for (String expectedDoc : expectedOrder) {
            int currentIndex = actualOrder.indexOf(expectedDoc);
            assertThat(currentIndex)
                    .as("Document '%s' should be in context", expectedDoc)
                    .isGreaterThanOrEqualTo(0);
            assertThat(currentIndex)
                    .as("Document '%s' should appear after previous document", expectedDoc)
                    .isGreaterThan(lastIndex);
            lastIndex = currentIndex;
        }
    }
}
