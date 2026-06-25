package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine._system.exception.BadRequestException;
import ai.myrmec.engine.IntegrationTestBase;
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
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link KnowledgeSyncScheduler} (#21 scheduled sync).
 * Drives the due-detection logic and the {@code runDueSyncs} pass against a
 * hermetic {@code file://} git repo so a due source is actually synced.
 */
@Transactional
class KnowledgeSyncSchedulerTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeSyncScheduler scheduler;

    private Path originRepo;

    @BeforeEach
    void createOriginRepo() throws Exception {
        originRepo = Files.createTempDirectory("myrmec-sched-origin-");
        try (Git git = Git.init().setDirectory(originRepo.toFile()).call()) {
            writeFile("README.md", "# Project\nWelcome.");
            writeFile("src/App.java", "class App {}");
            writeFile("docs/guide.md", "## Guide");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@myrmec.ai").setSign(false).call();
        }
    }

    @AfterEach
    void deleteOriginRepo() {
        deleteRecursively(originRepo);
    }

    @Test
    void runDueSyncsSyncsADueSource() {
        // Every-second cron, last synced two minutes ago -> due now.
        KnowledgeSource source = newScheduledSource("* * * * * *");
        source.setLastSyncAt(Instant.now().minusSeconds(120));
        knowledgeSourceRepository.save(source);

        scheduler.runDueSyncs();

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo("SUCCESS");
        assertThat(reloaded.getLastSyncChunks()).isEqualTo(3L);
        assertThat(knowledgeChunkRepository.countByKnowledgeSourceId(source.getId())).isEqualTo(3);
    }

    @Test
    void runDueSyncsSkipsASourceThatIsNotDue() {
        // Daily at 05:00, just synced -> next fire is up to a day away, not due.
        KnowledgeSource source = newScheduledSource("0 0 5 * * *");
        source.setLastSyncAt(Instant.now());
        knowledgeSourceRepository.save(source);

        scheduler.runDueSyncs();

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isNull();
        assertThat(knowledgeChunkRepository.countByKnowledgeSourceId(source.getId())).isZero();
    }

    @Test
    void runDueSyncsProcessesPushRequestedSourceAndClearsFlag() {
        // No cron schedule -> only eligible because a webhook stamped syncRequestedAt.
        KnowledgeSource source = newScheduledSource(null);
        source.setSyncRequestedAt(Instant.now());
        knowledgeSourceRepository.save(source);

        scheduler.runDueSyncs();

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo("SUCCESS");
        assertThat(reloaded.getLastSyncChunks()).isEqualTo(3L);
        assertThat(reloaded.getSyncRequestedAt()).isNull();
        assertThat(knowledgeChunkRepository.countByKnowledgeSourceId(source.getId())).isEqualTo(3);
    }

    @Test
    void runDueSyncsHandlesASourceThatIsBothPushRequestedAndCronDueWithoutError() {
        // Eligible via both paths; scheduler must sync it and not double-process.
        KnowledgeSource source = newScheduledSource("* * * * * *");
        source.setLastSyncAt(Instant.now().minusSeconds(120));
        source.setSyncRequestedAt(Instant.now());
        knowledgeSourceRepository.save(source);

        scheduler.runDueSyncs();

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo("SUCCESS");
        assertThat(reloaded.getLastSyncChunks()).isEqualTo(3L);
        assertThat(reloaded.getSyncRequestedAt()).isNull();
        assertThat(knowledgeChunkRepository.countByKnowledgeSourceId(source.getId())).isEqualTo(3);
    }

    @Test
    void isDueIsFalseForBlankSchedule() {
        KnowledgeSource source = new KnowledgeSource();
        source.setSyncSchedule("  ");
        source.setCreatedAt(Instant.now().minusSeconds(3600));
        assertThat(scheduler.isDue(source, Instant.now())).isFalse();
    }

    @Test
    void isDueFallsBackToCreatedAtForNeverSyncedSource() {
        KnowledgeSource source = new KnowledgeSource();
        source.setSyncSchedule("* * * * * *");
        source.setCreatedAt(Instant.now().minusSeconds(120));
        // Never synced; baseline = createdAt (2 min ago) -> due.
        assertThat(scheduler.isDue(source, Instant.now())).isTrue();
    }

    @Test
    void addSourceRejectsInvalidCron() {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "sched-badcron-" + UUID.randomUUID().toString().substring(0, 8),
                null, StubRetrievalProvider.PROVIDER_ID, null);

        assertThatThrownBy(() -> knowledgeBaseService.addSource(
                kb.getId(), GitConnector.CONNECTOR_TYPE, "repo", repoUri(), null, "not-a-cron"))
                .isInstanceOf(BadRequestException.class);
    }

    // --- helpers -------------------------------------------------------------

    private KnowledgeSource newScheduledSource(String cron) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "sched-kb-" + UUID.randomUUID().toString().substring(0, 8),
                null, StubRetrievalProvider.PROVIDER_ID, null);
        return knowledgeBaseService.addSource(
                kb.getId(), GitConnector.CONNECTOR_TYPE, "repo", repoUri(), null, cron);
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
