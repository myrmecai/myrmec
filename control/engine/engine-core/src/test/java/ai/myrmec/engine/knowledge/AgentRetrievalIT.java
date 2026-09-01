// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the agent retrieval pipeline (Domain E).
 *
 * <p>Tests the {@link AgentRetrievalService} directly (bypassing the
 * controller's agent-auth layer) to verify the core flow: session
 * validation, source pinning, stub provider dispatch, and error paths.</p>
 */
class AgentRetrievalIT extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private KnowledgeProviderService knowledgeProviderService;

    @Autowired
    private AgentRetrievalService retrievalService;

    @Autowired
    private ai.myrmec.engine.inference.SessionRepository sessionRepository;

    /**
     * Create a knowledge provider with a version configured to use the
     * stub retrieval provider, plus a knowledge source and some chunks.
     *
     * @return the knowledge source ID
     */
    private UUID setupKnowledgeBase(String namePrefix) {
        var provider = data.knowledgeProvider()
                .named(namePrefix + "-kp")
                .withType("EXTERNAL")
                .publishDraft()
                .withConfig(Map.of(
                        "providerId", "stub",
                        "responseMapping", Map.of(
                                "hitsPath", "$.results",
                                "passagePath", "text",
                                "sourceNamePath", "title",
                                "locatorPath", "url")))
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        var publishedVersion = knowledgeProviderService.getPublishedVersionOrNull(provider.getId());
        assertThat(publishedVersion).isNotNull();

        var source = data.knowledgeSource()
                .named(namePrefix + "-src")
                .forVersion(publishedVersion)
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        // Create chunks with keyword-findable content
        data.knowledgeChunk().forSource(source).withSequenceNo(0)
                .withContent("The quick brown fox jumps over the lazy dog").create();
        data.knowledgeChunk().forSource(source).withSequenceNo(1)
                .withContent("A quick guide to retrieval augmented generation").create();
        data.knowledgeChunk().forSource(source).withSequenceNo(2)
                .withContent("Completely unrelated text about cooking recipes").create();

        return source.getId();
    }

    /**
     * Create an ACTIVE session with the given knowledge source pinned.
     */
    private UUID createSessionWithPinnedSource(UUID projectId, UUID knowledgeSourceId) {
        ai.myrmec.engine.inference.Session session = new ai.myrmec.engine.inference.Session();
        session.setServiceType("CONVERSATION");
        session.setRefId(UUID.randomUUID());
        session.setProjectId(projectId);
        session.setStatus("ACTIVE");
        session.setContextPins(Map.of(
                "knowledgeSourceIds", List.of(knowledgeSourceId.toString())));
        return sessionRepository.save(session).getId();
    }

    @Test
    void retrieve_returnsHitsFromStubProvider() {
        var project = data.project().named("retr-proj").create();
        UUID sourceId = setupKnowledgeBase("retr");
        UUID sessionId = createSessionWithPinnedSource(project.getId(), sourceId);

        RetrievalRequest request = new RetrievalRequest(
                sourceId, "quick", 5, null, sessionId, null, null);

        List<RetrievalHit> hits = retrievalService.retrieve(UUID.randomUUID(), request);

        // "quick" matches two chunks (fox + guide), not the cooking one
        assertThat(hits).hasSize(2);
        assertThat(hits).allSatisfy(hit -> {
            assertThat(hit.passage()).isNotNull();
            assertThat(hit.sourceId()).isEqualTo(sourceId);
            assertThat(hit.score()).isEqualTo(1.0);
        });
    }

    @Test
    void retrieve_noSessionReturns403() {
        UUID sourceId = setupKnowledgeBase("retr-nosess");

        RetrievalRequest request = new RetrievalRequest(
                sourceId, "quick", 5, null, UUID.randomUUID(), null, null);

        assertThatThrownBy(() -> retrievalService.retrieve(UUID.randomUUID(), request))
                .isInstanceOf(RetrievalForbiddenException.class)
                .hasMessageContaining("No active session");
    }

    @Test
    void retrieve_sourceNotPinnedReturns403() {
        var project = data.project().named("retr-unpinned-proj").create();
        UUID sourceId = setupKnowledgeBase("retr-unpinned");

        // Create a session but pin a DIFFERENT source
        UUID otherSourceId = UUID.randomUUID();
        UUID sessionId = createSessionWithPinnedSource(project.getId(), otherSourceId);

        RetrievalRequest request = new RetrievalRequest(
                sourceId, "quick", 5, null, sessionId, null, null);

        assertThatThrownBy(() -> retrievalService.retrieve(UUID.randomUUID(), request))
                .isInstanceOf(RetrievalForbiddenException.class)
                .hasMessageContaining("not pinned");
    }

    @Test
    void retrieve_noMatchingChunksReturnsEmptyHits() {
        var project = data.project().named("retr-empty-proj").create();
        UUID sourceId = setupKnowledgeBase("retr-empty");
        UUID sessionId = createSessionWithPinnedSource(project.getId(), sourceId);

        // Query that doesn't match any chunk content
        RetrievalRequest request = new RetrievalRequest(
                sourceId, "quantum", 5, null, sessionId, null, null);

        List<RetrievalHit> hits = retrievalService.retrieve(UUID.randomUUID(), request);
        assertThat(hits).isEmpty();
    }

    @Test
    void retrieve_unknownProviderIdReturns404() {
        var project = data.project().named("retr-badprov-proj").create();

        // Create a provider with config pointing to a non-existent provider id
        var provider = data.knowledgeProvider()
                .named("retr-bad-kp")
                .withType("EXTERNAL")
                .publishDraft()
                .withConfig(Map.of(
                        "providerId", "nonexistent-provider",
                        "responseMapping", Map.of(
                                "hitsPath", "$.results",
                                "passagePath", "text",
                                "sourceNamePath", "title",
                                "locatorPath", "url")))
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        var publishedVersion = knowledgeProviderService.getPublishedVersionOrNull(provider.getId());

        var source = data.knowledgeSource()
                .named("retr-bad-src")
                .forVersion(publishedVersion)
                .withActor(TEST_ADMIN_ID, TEST_ADMIN_NAME)
                .create();

        UUID sessionId = createSessionWithPinnedSource(project.getId(), source.getId());

        RetrievalRequest request = new RetrievalRequest(
                source.getId(), "test", 5, null, sessionId, null, null);

        assertThatThrownBy(() -> retrievalService.retrieve(UUID.randomUUID(), request))
                .isInstanceOf(RetrievalProviderNotFoundException.class)
                .hasMessageContaining("nonexistent-provider");
    }

    @Test
    void retrieve_topKLimitsResults() {
        var project = data.project().named("retr-topk-proj").create();
        UUID sourceId = setupKnowledgeBase("retr-topk");
        UUID sessionId = createSessionWithPinnedSource(project.getId(), sourceId);

        // topK=1 — should only return 1 of the 2 "quick" matches
        RetrievalRequest request = new RetrievalRequest(
                sourceId, "quick", 1, null, sessionId, null, null);

        List<RetrievalHit> hits = retrievalService.retrieve(UUID.randomUUID(), request);
        assertThat(hits).hasSize(1);
    }
}