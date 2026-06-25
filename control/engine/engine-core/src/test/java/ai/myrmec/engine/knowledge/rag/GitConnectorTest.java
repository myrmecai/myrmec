package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.SyncResult;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link GitConnector} (#21). Builds a real local git
 * repository on disk with jgit, then drives a sync through
 * {@link ConnectorDispatcher} against a {@code file://} URI so the test is
 * hermetic (no network). Verifies text-file emission, binary skipping, glob /
 * subdirectory filters, metadata, and clone-failure handling.
 */
@Transactional
class GitConnectorTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeSourceRepository knowledgeSourceRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    private Path originRepo;

    @BeforeEach
    void createOriginRepo() throws Exception {
        originRepo = Files.createTempDirectory("myrmec-git-origin-");
        try (Git git = Git.init().setDirectory(originRepo.toFile()).call()) {
            writeFile("README.md", "# Project\nWelcome to the Myrmec sample repo.");
            writeFile("src/App.java", "class App { void run() {} }");
            writeFile("docs/guide.md", "## Guide\nHow to use the assistant.");
            // Binary asset: contains a NUL byte → must be skipped.
            Files.write(originRepo.resolve("logo.png"), new byte[]{(byte) 0x89, 0x50, 0x00, 0x4E, 0x47});
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("initial commit")
                    .setAuthor("Test", "test@myrmec.ai")
                    .setSign(false)
                    .call();
        }
    }

    @AfterEach
    void deleteOriginRepo() {
        deleteRecursively(originRepo);
    }

    @Test
    void gitConnectorIsRegistered() {
        assertThat(connectorDispatcher.connectorsByType()).containsKey(GitConnector.CONNECTOR_TYPE);
    }

    @Test
    void clonesRepoAndEmitsTextFilesSkippingBinary() throws ConnectorException {
        var source = newGitSource("git-clone-kb", repoUri(), null);

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        assertThat(result.chunksEmitted()).isEqualTo(3);

        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder("README.md", "src/App.java", "docs/guide.md");
        assertThat(chunks).noneMatch(c -> c.getLocator().equals("logo.png"));

        KnowledgeChunk readme = chunks.stream()
                .filter(c -> c.getLocator().equals("README.md")).findFirst().orElseThrow();
        assertThat(readme.getContent()).contains("Welcome to the Myrmec sample repo.");
        assertThat(readme.getMetadataJson()).contains("\"commit\"").contains("\"ext\":\"md\"");
    }

    @Test
    void includeGlobsRestrictToMatchingPaths() throws ConnectorException {
        var source = newGitSource("git-include-kb", repoUri(), "{\"includeGlobs\":[\"src/**\"]}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator).containsExactly("src/App.java");
    }

    @Test
    void subdirectoryRestrictsScope() throws ConnectorException {
        var source = newGitSource("git-subdir-kb", repoUri(), "{\"subdirectory\":\"docs\"}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator).containsExactly("docs/guide.md");
    }

    @Test
    void excludeGlobsSkipMatchingPaths() throws ConnectorException {
        var source = newGitSource("git-exclude-kb", repoUri(), "{\"excludeGlobs\":[\"**/*.md\"]}");

        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator).containsExactly("src/App.java");
    }

    @Test
    void unreachableRepoThrowsConnectorExceptionAndMarksFailed() {
        var source = newGitSource("git-bad-kb",
                Path.of(System.getProperty("java.io.tmpdir"), "myrmec-no-such-repo-" + UUID.randomUUID())
                        .toUri().toString(),
                null);

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncResult.Status.FAILED.name());
    }

    // --- helpers -------------------------------------------------------------

    private KnowledgeSource newGitSource(String kbName, String uri, String configJson) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                kbName + "-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        return knowledgeBaseService.addSource(
                kb.getId(),
                GitConnector.CONNECTOR_TYPE,
                "repo",
                uri,
                configJson,
                null);
    }

    private String repoUri() {
        return originRepo.toUri().toString();
    }

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
