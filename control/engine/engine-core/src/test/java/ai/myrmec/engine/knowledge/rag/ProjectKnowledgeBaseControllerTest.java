package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.knowledge.rag.dto.CreateKnowledgeBaseRequest;
import ai.myrmec.engine.knowledge.rag.dto.CreateKnowledgeSourceRequest;
import ai.myrmec.engine.knowledge.rag.dto.KnowledgeBaseResponse;
import ai.myrmec.engine.knowledge.rag.dto.KnowledgeCapabilitiesResponse;
import ai.myrmec.engine.knowledge.rag.dto.KnowledgeSourceResponse;
import ai.myrmec.engine.knowledge.rag.dto.SyncResultResponse;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST integration test for {@link ProjectKnowledgeBaseController} and
 * {@link KnowledgeCapabilitiesController} (#31). Drives the management surface
 * over HTTP — create / list / get / delete knowledge bases and sources, the
 * manual sync trigger (against a hermetic {@code file://} git repo), the
 * cross-project isolation guard, and the capabilities catalogue.
 *
 * <p>Not {@code @Transactional} — the embedded HTTP server runs on a separate
 * thread (see {@link GitConnectorTest} / {@code ConversationControllerTest}).</p>
 */
class ProjectKnowledgeBaseControllerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    private Path originRepo;

    @BeforeEach
    void createOriginRepo() throws Exception {
        originRepo = Files.createTempDirectory("myrmec-kbctrl-origin-");
        try (Git git = Git.init().setDirectory(originRepo.toFile()).call()) {
            writeFile("README.md", "# Project\nWelcome to the sample repo.");
            writeFile("src/App.java", "class App { void run() {} }");
            writeFile("docs/guide.md", "## Guide\nHow to use it.");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@myrmec.ai").setSign(false).call();
        }
    }

    @AfterEach
    void deleteOriginRepo() {
        deleteRecursively(originRepo);
    }

    @Test
    void createListGetDeleteKnowledgeBase() {
        Project project = data.project().named("kbctrl-crud").create();
        HttpHeaders editor = userHeaders(TEST_ADMIN_ID, project.getId());
        String base = "/api/v1/projects/" + project.getId() + "/knowledge-bases";

        // Create.
        var createBody = new CreateKnowledgeBaseRequest("Docs KB", "team docs", null, null);
        ResponseEntity<KnowledgeBaseResponse> created = restTemplate.exchange(
                base, HttpMethod.POST, new HttpEntity<>(createBody, editor), KnowledgeBaseResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        UUID kbId = created.getBody().id();
        assertThat(created.getBody().scope()).isEqualTo("PROJECT");
        assertThat(created.getBody().projectId()).isEqualTo(project.getId());
        assertThat(created.getBody().providerId()).isEqualTo(StubRetrievalProvider.PROVIDER_ID);
        assertThat(created.getBody().sourceCount()).isZero();

        // List.
        ResponseEntity<List<KnowledgeBaseResponse>> listed = restTemplate.exchange(
                base, HttpMethod.GET, new HttpEntity<>(editor),
                new ParameterizedTypeReference<List<KnowledgeBaseResponse>>() {});
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).extracting(KnowledgeBaseResponse::id).contains(kbId);

        // Get by id.
        ResponseEntity<KnowledgeBaseResponse> fetched = restTemplate.exchange(
                base + "/" + kbId, HttpMethod.GET, new HttpEntity<>(editor), KnowledgeBaseResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().name()).isEqualTo("Docs KB");

        // Delete.
        ResponseEntity<Void> deleted = restTemplate.exchange(
                base + "/" + kbId, HttpMethod.DELETE, new HttpEntity<>(editor), Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<KnowledgeBaseResponse> gone = restTemplate.exchange(
                base + "/" + kbId, HttpMethod.GET, new HttpEntity<>(editor), KnowledgeBaseResponse.class);
        assertThat(gone.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void addSourceThenManualSyncEmitsChunks() {
        Project project = data.project().named("kbctrl-sync").create();
        HttpHeaders editor = userHeaders(TEST_ADMIN_ID, project.getId());
        String base = "/api/v1/projects/" + project.getId() + "/knowledge-bases";

        UUID kbId = restTemplate.exchange(base, HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest("Sync KB", null, null, null), editor),
                KnowledgeBaseResponse.class).getBody().id();

        // Add a git source pointing at the hermetic file:// repo.
        var sourceBody = new CreateKnowledgeSourceRequest(
                GitConnector.CONNECTOR_TYPE, "repo", originRepo.toUri().toString(), null, null);
        ResponseEntity<KnowledgeSourceResponse> addedSource = restTemplate.exchange(
                base + "/" + kbId + "/sources", HttpMethod.POST,
                new HttpEntity<>(sourceBody, editor), KnowledgeSourceResponse.class);
        assertThat(addedSource.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sourceId = addedSource.getBody().id();
        assertThat(addedSource.getBody().connectorType()).isEqualTo(GitConnector.CONNECTOR_TYPE);
        assertThat(addedSource.getBody().chunkCount()).isZero();

        // Trigger a manual sync.
        ResponseEntity<SyncResultResponse> sync = restTemplate.exchange(
                base + "/" + kbId + "/sources/" + sourceId + "/sync", HttpMethod.POST,
                new HttpEntity<>(editor), SyncResultResponse.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sync.getBody().status()).isEqualTo("SUCCESS");
        assertThat(sync.getBody().chunksEmitted()).isEqualTo(3);

        // Source listing now reflects last-sync bookkeeping + chunk count.
        ResponseEntity<List<KnowledgeSourceResponse>> sources = restTemplate.exchange(
                base + "/" + kbId + "/sources", HttpMethod.GET, new HttpEntity<>(editor),
                new ParameterizedTypeReference<List<KnowledgeSourceResponse>>() {});
        assertThat(sources.getBody()).hasSize(1);
        KnowledgeSourceResponse s = sources.getBody().get(0);
        assertThat(s.lastSyncStatus()).isEqualTo("SUCCESS");
        assertThat(s.chunkCount()).isEqualTo(3);

        // Delete the source.
        ResponseEntity<Void> del = restTemplate.exchange(
                base + "/" + kbId + "/sources/" + sourceId, HttpMethod.DELETE,
                new HttpEntity<>(editor), Void.class);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void manualSyncOfUnreachableRepoReturnsFailedStatus() {
        Project project = data.project().named("kbctrl-syncfail").create();
        HttpHeaders editor = userHeaders(TEST_ADMIN_ID, project.getId());
        String base = "/api/v1/projects/" + project.getId() + "/knowledge-bases";

        UUID kbId = restTemplate.exchange(base, HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest("Fail KB", null, null, null), editor),
                KnowledgeBaseResponse.class).getBody().id();

        String badUri = Path.of(System.getProperty("java.io.tmpdir"), "no-such-repo-" + UUID.randomUUID())
                .toUri().toString();
        UUID sourceId = restTemplate.exchange(base + "/" + kbId + "/sources", HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeSourceRequest(
                        GitConnector.CONNECTOR_TYPE, "repo", badUri, null, null), editor),
                KnowledgeSourceResponse.class).getBody().id();

        ResponseEntity<SyncResultResponse> sync = restTemplate.exchange(
                base + "/" + kbId + "/sources/" + sourceId + "/sync", HttpMethod.POST,
                new HttpEntity<>(editor), SyncResultResponse.class);
        assertThat(sync.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sync.getBody().status()).isEqualTo("FAILED");
        assertThat(sync.getBody().errorCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void crossProjectKnowledgeBaseAccessReturnsNotFound() {
        Project projectA = data.project().named("kbctrl-iso-a").create();
        Project projectB = data.project().named("kbctrl-iso-b").create();
        HttpHeaders editorA = userHeaders(TEST_ADMIN_ID, projectA.getId());
        HttpHeaders editorB = userHeaders(TEST_ADMIN_ID, projectB.getId());

        UUID kbId = restTemplate.exchange(
                "/api/v1/projects/" + projectA.getId() + "/knowledge-bases", HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest("Private KB", null, null, null), editorA),
                KnowledgeBaseResponse.class).getBody().id();

        // Reaching project A's KB through project B's path must 404.
        ResponseEntity<KnowledgeBaseResponse> crossed = restTemplate.exchange(
                "/api/v1/projects/" + projectB.getId() + "/knowledge-bases/" + kbId,
                HttpMethod.GET, new HttpEntity<>(editorB), KnowledgeBaseResponse.class);
        assertThat(crossed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownConnectorTypeReturnsBadRequest() {
        Project project = data.project().named("kbctrl-badconn").create();
        HttpHeaders editor = userHeaders(TEST_ADMIN_ID, project.getId());
        String base = "/api/v1/projects/" + project.getId() + "/knowledge-bases";

        UUID kbId = restTemplate.exchange(base, HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest("Conn KB", null, null, null), editor),
                KnowledgeBaseResponse.class).getBody().id();

        ResponseEntity<String> bad = restTemplate.exchange(
                base + "/" + kbId + "/sources", HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeSourceRequest(
                        "bogus", "repo", "https://example.invalid/x.git", null, null), editor),
                String.class);
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void capabilitiesListConnectorTypesAndProviderIds() {
        ResponseEntity<KnowledgeCapabilitiesResponse> caps = restTemplate.exchange(
                "/api/v1/knowledge/capabilities", HttpMethod.GET,
                new HttpEntity<>(adminHeaders()), KnowledgeCapabilitiesResponse.class);
        assertThat(caps.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(caps.getBody().connectorTypes()).contains(GitConnector.CONNECTOR_TYPE);
        assertThat(caps.getBody().providerIds()).contains(StubRetrievalProvider.PROVIDER_ID);
    }

    // --- helpers -------------------------------------------------------------

    private void writeFile(String relPath, String content) throws IOException {
        Path file = originRepo.resolve(relPath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
