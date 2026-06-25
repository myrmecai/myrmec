package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.spi.connector.ConnectorException;
import ai.myrmec.engine.spi.connector.SyncResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@link S3Connector} (#22). Drives a sync through
 * {@link ConnectorDispatcher} exactly like the other connector tests, but the
 * S3 storage is swapped for an in-memory {@link ObjectStoreFactory}
 * ({@code @Primary} in {@link InMemoryStoreConfig}) so the test is hermetic —
 * no Docker, LocalStack, or live bucket. Verifies prefix listing, type/glob
 * filtering, PDF + DOCX extraction, partial-failure handling, and the
 * unreachable-bucket path.
 */
@Transactional
@Import(S3ConnectorTest.InMemoryStoreConfig.class)
class S3ConnectorTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeSourceRepository knowledgeSourceRepository;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Autowired
    private ConnectorDispatcher connectorDispatcher;

    @Autowired
    private InMemoryObjectStoreFactory store;

    private static final String BUCKET = "docs";

    @BeforeEach
    void seedBucket() {
        store.clear();
    }

    @AfterEach
    void clearBucket() {
        store.clear();
    }

    @Test
    void s3ConnectorIsRegistered() {
        assertThat(connectorDispatcher.connectorsByType()).containsKey(S3Connector.CONNECTOR_TYPE);
    }

    @Test
    void ingestsSupportedTextFilesUnderPrefixAndSkipsBinariesAndOtherPrefixes() throws ConnectorException {
        store.put(BUCKET, "handbook/readme.md", "# Handbook\nWelcome.".getBytes(StandardCharsets.UTF_8));
        store.put(BUCKET, "handbook/guide.txt", "Step one. Step two.".getBytes(StandardCharsets.UTF_8));
        store.put(BUCKET, "handbook/logo.png", new byte[]{0, 1, 2, 3}); // unsupported binary
        store.put(BUCKET, "policies/other.md", "Out of prefix.".getBytes(StandardCharsets.UTF_8));

        var source = newS3Source("s3-prefix-kb", "s3://" + BUCKET + "/handbook/", null);
        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator)
                .containsExactlyInAnyOrder("handbook/readme.md", "handbook/guide.txt");
        KnowledgeChunk readme = chunks.stream()
                .filter(c -> c.getLocator().equals("handbook/readme.md")).findFirst().orElseThrow();
        assertThat(readme.getContent()).contains("Welcome.");
        assertThat(readme.getMetadataJson()).contains("\"bucket\":\"docs\"").contains("\"ext\":\"md\"");
    }

    @Test
    void includeExtensionsRestrictsToNamedTypes() throws ConnectorException {
        store.put(BUCKET, "a.md", "alpha".getBytes(StandardCharsets.UTF_8));
        store.put(BUCKET, "b.txt", "beta".getBytes(StandardCharsets.UTF_8));

        var source = newS3Source("s3-ext-kb", "s3://" + BUCKET + "/", "{\"includeExtensions\":[\"md\"]}");
        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator).containsExactly("a.md");
    }

    @Test
    void excludeGlobsSkipMatchingKeys() throws ConnectorException {
        store.put(BUCKET, "keep.md", "keep".getBytes(StandardCharsets.UTF_8));
        store.put(BUCKET, "drafts/skip.md", "skip".getBytes(StandardCharsets.UTF_8));

        var source = newS3Source("s3-exclude-kb", "s3://" + BUCKET + "/", "{\"excludeGlobs\":[\"**/drafts/**\"]}");
        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator).containsExactly("keep.md");
    }

    @Test
    void extractsTextFromPdf() throws ConnectorException, IOException {
        store.put(BUCKET, "report.pdf", pdfBytes("Quarterly revenue grew sharply."));

        var source = newS3Source("s3-pdf-kb", "s3://" + BUCKET + "/", null);
        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getLocator()).isEqualTo("report.pdf");
        assertThat(chunks.get(0).getContent()).contains("Quarterly revenue grew sharply.");
    }

    @Test
    void extractsTextFromDocx() throws ConnectorException, IOException {
        store.put(BUCKET, "memo.docx", docxBytes("Hello docx world.", "Second paragraph."));

        var source = newS3Source("s3-docx-kb", "s3://" + BUCKET + "/", null);
        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.SUCCESS);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getContent())
                .contains("Hello docx world.")
                .contains("Second paragraph.");
    }

    @Test
    void perObjectExtractionFailureIsRecordedAsPartial() throws ConnectorException {
        store.put(BUCKET, "good.md", "fine".getBytes(StandardCharsets.UTF_8));
        store.put(BUCKET, "broken.pdf", "not a real pdf".getBytes(StandardCharsets.UTF_8));

        var source = newS3Source("s3-partial-kb", "s3://" + BUCKET + "/", null);
        SyncResult result = connectorDispatcher.sync(source.getId());

        assertThat(result.status()).isEqualTo(SyncResult.Status.PARTIAL);
        assertThat(result.errors()).hasSize(1);
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findByKnowledgeSourceId(source.getId());
        assertThat(chunks).extracting(KnowledgeChunk::getLocator).containsExactly("good.md");
    }

    @Test
    void unreachableBucketThrowsConnectorExceptionAndMarksFailed() {
        var source = newS3Source("s3-bad-kb", "s3://missing-bucket/", null);

        assertThatThrownBy(() -> connectorDispatcher.sync(source.getId()))
                .isInstanceOf(ConnectorException.class);

        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getLastSyncStatus()).isEqualTo(SyncResult.Status.FAILED.name());
    }

    // --- helpers -------------------------------------------------------------

    private KnowledgeSource newS3Source(String kbName, String uri, String configJson) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                kbName + "-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        return knowledgeBaseService.addSource(
                kb.getId(),
                S3Connector.CONNECTOR_TYPE,
                "bucket",
                uri,
                configJson,
                null);
    }

    private byte[] pdfBytes(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    private byte[] docxBytes(String... paragraphs) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            StringBuilder xml = new StringBuilder("<w:document><w:body>");
            for (String p : paragraphs) {
                xml.append("<w:p><w:r><w:t>").append(p).append("</w:t></w:r></w:p>");
            }
            xml.append("</w:body></w:document>");
            zip.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    // --- in-memory object store ----------------------------------------------

    @TestConfiguration
    static class InMemoryStoreConfig {
        @Bean
        @Primary
        InMemoryObjectStoreFactory inMemoryObjectStoreFactory() {
            return new InMemoryObjectStoreFactory();
        }
    }

    /** Hermetic {@link ObjectStoreFactory} backed by an in-memory bucket map. */
    static class InMemoryObjectStoreFactory implements ObjectStoreFactory {

        private final Map<String, Map<String, byte[]>> buckets = new ConcurrentHashMap<>();

        void put(String bucket, String key, byte[] bytes) {
            buckets.computeIfAbsent(bucket, b -> new LinkedHashMap<>()).put(key, bytes);
        }

        void clear() {
            buckets.clear();
        }

        @Override
        public ObjectStore create(Settings settings) {
            return new InMemoryObjectStore(buckets);
        }
    }

    private record InMemoryObjectStore(Map<String, Map<String, byte[]>> buckets) implements ObjectStore {

        @Override
        public List<Entry> list(String bucket, String prefix) throws IOException {
            Map<String, byte[]> objects = buckets.get(bucket);
            if (objects == null) {
                throw new IOException("no such bucket: " + bucket);
            }
            List<Entry> entries = new ArrayList<>();
            for (Map.Entry<String, byte[]> object : objects.entrySet()) {
                if (prefix == null || prefix.isEmpty() || object.getKey().startsWith(prefix)) {
                    entries.add(new Entry(object.getKey(), object.getValue().length));
                }
            }
            return entries;
        }

        @Override
        public byte[] read(String bucket, String key) throws IOException {
            Map<String, byte[]> objects = buckets.get(bucket);
            byte[] bytes = objects == null ? null : objects.get(key);
            if (bytes == null) {
                throw new IOException("no such object: " + key);
            }
            return bytes;
        }

        @Override
        public void close() {
            // nothing to release
        }
    }
}
