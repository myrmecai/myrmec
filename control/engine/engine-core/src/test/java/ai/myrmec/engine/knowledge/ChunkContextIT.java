// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ChunkContextService} and
 * {@link ChunkContextController}.
 *
 * <p>Uses {@link TestDataBuilder} to create knowledge sources and chunks,
 * then verifies the context endpoint returns the anchor plus neighbours.</p>
 */
class ChunkContextIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private ChunkContextService chunkContextService;

    @Autowired
    private KnowledgeChunkRepository knowledgeChunkRepository;

    @Test
    void getContext_returnsAnchorAndNeighbours() {
        // Arrange: create a provider+version+source, then 5 chunks
        var provider = data.knowledgeProvider()
                .named("ctx-kp")
                .publishDraft()
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();
        var draft = knowledgeProviderService.getDraftVersion(provider.getId());
        // After publishDraft(), the version is PUBLISHED; use that id
        var publishedVersion = knowledgeProviderService.getPublishedVersionOrNull(provider.getId());
        assertThat(publishedVersion).isNotNull();

        var source = data.knowledgeSource()
                .named("ctx-src")
                .forVersion(publishedVersion)
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        // Create 5 chunks with sequence numbers 0..4
        KnowledgeChunk chunk0 = data.knowledgeChunk().forSource(source).withSequenceNo(0).withContent("chunk-0").create();
        KnowledgeChunk chunk1 = data.knowledgeChunk().forSource(source).withSequenceNo(1).withContent("chunk-1").create();
        KnowledgeChunk chunk2 = data.knowledgeChunk().forSource(source).withSequenceNo(2).withContent("chunk-2").create();
        KnowledgeChunk chunk3 = data.knowledgeChunk().forSource(source).withSequenceNo(3).withContent("chunk-3").create();
        KnowledgeChunk chunk4 = data.knowledgeChunk().forSource(source).withSequenceNo(4).withContent("chunk-4").create();

        // Act: get context for the middle chunk (seq=2, window=2 → neighbours 0..4)
        ChunkContext ctx = chunkContextService.getContext(chunk2.getId(), source.getId());

        // Assert: anchor is chunk2, neighbours include all 5 (seq 0..4)
        assertThat(ctx.anchor().getId()).isEqualTo(chunk2.getId());
        assertThat(ctx.neighbours()).hasSize(5);
        assertThat(ctx.neighbours()).extracting(KnowledgeChunk::getSequenceNo)
                .containsExactlyInAnyOrder(0L, 1L, 2L, 3L, 4L);
    }

    @Test
    void getContext_atBoundaryReturnsLimitedNeighbours() {
        var provider = data.knowledgeProvider()
                .named("ctx-boundary-kp")
                .publishDraft()
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();
        var publishedVersion = knowledgeProviderService.getPublishedVersionOrNull(provider.getId());

        var source = data.knowledgeSource()
                .named("ctx-boundary-src")
                .forVersion(publishedVersion)
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        // Create 3 chunks: seq 0, 1, 2
        KnowledgeChunk chunk0 = data.knowledgeChunk().forSource(source).withSequenceNo(0).withContent("first").create();
        KnowledgeChunk chunk1 = data.knowledgeChunk().forSource(source).withSequenceNo(1).withContent("second").create();
        KnowledgeChunk chunk2 = data.knowledgeChunk().forSource(source).withSequenceNo(2).withContent("third").create();

        // Get context for chunk0 (seq=0, window=2 → start=max(0, -2)=0, end=2)
        ChunkContext ctx = chunkContextService.getContext(chunk0.getId(), source.getId());

        assertThat(ctx.anchor().getId()).isEqualTo(chunk0.getId());
        // Neighbours: seq 0..2 = all 3 chunks
        assertThat(ctx.neighbours()).hasSize(3);
    }

    @Test
    void getContext_wrongSourceIdThrowsNotFound() {
        var provider = data.knowledgeProvider()
                .named("ctx-wrong-kp")
                .publishDraft()
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();
        var publishedVersion = knowledgeProviderService.getPublishedVersionOrNull(provider.getId());

        var source = data.knowledgeSource()
                .named("ctx-wrong-src")
                .forVersion(publishedVersion)
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        KnowledgeChunk chunk = data.knowledgeChunk()
                .forSource(source).withSequenceNo(0).withContent("solo").create();

        // Use a random different source id
        UUID wrongSourceId = UUID.randomUUID();

        try {
            chunkContextService.getContext(chunk.getId(), wrongSourceId);
            assertThat(false).as("Should have thrown ResourceNotFoundException").isTrue();
        } catch (ai.myrmec.engine._system.exception.ResourceNotFoundException e) {
            assertThat(e.getMessage()).contains(chunk.getId().toString());
        }
    }

    @Test
    void getContext_nonExistentChunkThrowsNotFound() {
        UUID fakeChunkId = UUID.randomUUID();
        UUID fakeSourceId = UUID.randomUUID();

        try {
            chunkContextService.getContext(fakeChunkId, fakeSourceId);
            assertThat(false).as("Should have thrown ResourceNotFoundException").isTrue();
        } catch (ai.myrmec.engine._system.exception.ResourceNotFoundException e) {
            assertThat(e.getMessage()).contains(fakeChunkId.toString());
        }
    }

    @Test
    void getContext_chunkWithoutSequenceReturnsOnlyAnchor() {
        var provider = data.knowledgeProvider()
                .named("ctx-no-seq-kp")
                .publishDraft()
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();
        var publishedVersion = knowledgeProviderService.getPublishedVersionOrNull(provider.getId());

        var source = data.knowledgeSource()
                .named("ctx-no-seq-src")
                .forVersion(publishedVersion)
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        // Create chunk with null sequence (default is 0 in builder; override)
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setKnowledgeSourceId(source.getId());
        chunk.setLocator("doc.md#no-seq-" + System.nanoTime());
        chunk.setContent("no-seq-content");
        chunk.setContentHash("hash123");
        chunk.setSequenceNo(null);
        chunk = knowledgeChunkRepository.save(chunk);

        ChunkContext ctx = chunkContextService.getContext(chunk.getId(), source.getId());

        assertThat(ctx.anchor().getId()).isEqualTo(chunk.getId());
        assertThat(ctx.neighbours()).isEmpty();
    }

    @Autowired
    private KnowledgeProviderService knowledgeProviderService;
}