// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.knowledge.rag.dto.ChunkContextResponse;
import ai.myrmec.engine.knowledge.rag.dto.CreateKnowledgeBaseRequest;
import ai.myrmec.engine.knowledge.rag.dto.CreateKnowledgeSourceRequest;
import ai.myrmec.engine.knowledge.rag.dto.KnowledgeBaseResponse;
import ai.myrmec.engine.knowledge.rag.dto.KnowledgeSourceResponse;
import ai.myrmec.engine.knowledge.rag.dto.SyncResultResponse;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * REST integration test for {@link ChunkContextController} (#30 citation side
 * panel). Verifies the user-facing chunk-context preview: a 200 returning the
 * cited passage plus its neighbours, a 403 when the caller lacks project view
 * access, and a 404 for an unknown chunk. Chunks are produced through the real
 * sync path against a hermetic {@code file://} git repo.
 *
 * <p>Not {@code @Transactional} - the embedded HTTP server runs on a separate
 * thread.</p>
 */
class ChunkContextControllerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    private Path originRepo;

    @BeforeEach
    void createOriginRepo() throws Exception {
        originRepo = Files.createTempDirectory("myrmec-chunkctx-origin-");
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

    private record Seeded(UUID projectId, UUID kbId, UUID sourceId) {
    }

    private Seeded seedSyncedKnowledgeBase(String name, HttpHeaders editor, UUID projectId) {
        String base = "/api/v1/projects/" + projectId + "/knowledge-bases";
        UUID kbId = restTemplate.exchange(base, HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest(name, null, null, null), editor),
                KnowledgeBaseResponse.class).getBody().id();
        UUID sourceId = restTemplate.exchange(base + "/" + kbId + "/sources", HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeSourceRequest(
                        GitConnector.CONNECTOR_TYPE, "repo", originRepo.toUri().toString(), null, null), editor),
                KnowledgeSourceResponse.class).getBody().id();
        ResponseEntity<SyncResultResponse> sync = restTemplate.exchange(
                base + "/" + kbId + "/sources/" + sourceId + "/sync", HttpMethod.POST,
                new HttpEntity<>(editor), SyncResultResponse.class);
        assertThat(sync.getBody().chunksEmitted()).isEqualTo(3);
        return new Seeded(projectId, kbId, sourceId);
    }

    @Test
    void returnsCitedPassageWithNeighbours() {
        Project project = data.project().named("chunkctx-ok").create();
        HttpHeaders editor = userHeaders(TEST_ADMIN_ID, project.getId());
        Seeded s = seedSyncedKnowledgeBase("Ctx KB", editor, project.getId());

        List<KnowledgeChunk> ordered =
                knowledgeChunkRepository.findByKnowledgeSourceIdOrderByCreatedAtAscIdAsc(s.sourceId());
        assertThat(ordered).hasSize(3);
        KnowledgeChunk middle = ordered.get(1);

        String url = "/api/v1/projects/" + s.projectId() + "/knowledge-bases/" + s.kbId()
                + "/chunks/" + middle.getId() + "/context";
        ResponseEntity<ChunkContextResponse> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(editor), ChunkContextResponse.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ChunkContextResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.chunkId()).isEqualTo(middle.getId());
        assertThat(body.sourceId()).isEqualTo(s.sourceId());
        assertThat(body.sourceName()).isEqualTo("repo");
        assertThat(body.locator()).isEqualTo(middle.getLocator());
        assertThat(body.passage()).isEqualTo(middle.getContent());
        // The two non-target chunks must appear as neighbours, order-independent.
        assertThat(body.before()).hasSize(1);
        assertThat(body.after()).hasSize(1);
        assertThat(body.before()).containsExactly(ordered.get(0).getContent());
        assertThat(body.after()).containsExactly(ordered.get(2).getContent());
    }

    @Test
    void chunkContextForUnviewableProjectReturnsForbidden() {
        Project project = data.project().named("chunkctx-forbidden").create();
        HttpHeaders editor = userHeaders(TEST_ADMIN_ID, project.getId());
        Seeded s = seedSyncedKnowledgeBase("Forbidden KB", editor, project.getId());
        UUID chunkId = knowledgeChunkRepository
                .findByKnowledgeSourceIdOrderByCreatedAtAscIdAsc(s.sourceId()).get(0).getId();

        // Token scoped to an unrelated project - no view access to this project.
        HttpHeaders outsider = userHeaders(TEST_ADMIN_ID, UUID.randomUUID());
        String url = "/api/v1/projects/" + s.projectId() + "/knowledge-bases/" + s.kbId()
                + "/chunks/" + chunkId + "/context";
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(outsider), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownChunkReturnsNotFound() {
        Project project = data.project().named("chunkctx-missing").create();
        HttpHeaders editor = userHeaders(TEST_ADMIN_ID, project.getId());
        String base = "/api/v1/projects/" + project.getId() + "/knowledge-bases";
        UUID kbId = restTemplate.exchange(base, HttpMethod.POST,
                new HttpEntity<>(new CreateKnowledgeBaseRequest("Empty KB", null, null, null), editor),
                KnowledgeBaseResponse.class).getBody().id();

        String url = base + "/" + kbId + "/chunks/" + UUID.randomUUID() + "/context";
        ResponseEntity<String> resp = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(editor), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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