// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.attachment;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.attachment.dto.AttachmentResponse;
import ai.myrmec.engine.attachment.dto.PromoteAttachmentRequest;
import ai.myrmec.engine.attachment.dto.PromoteAttachmentResponse;
import ai.myrmec.engine.conversation.dto.ConversationResponse;
import ai.myrmec.engine.conversation.dto.CreateConversationRequest;
import ai.myrmec.engine.knowledge.rag.KnowledgeBase;
import ai.myrmec.engine.knowledge.rag.KnowledgeBaseService;
import ai.myrmec.engine.knowledge.rag.KnowledgeChunk;
import ai.myrmec.engine.knowledge.rag.StubRetrievalProvider;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.testing.TestDataBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REST integration test for promote-to-KB (#103-C). Exercises the full HTTP
 * surface end to end: upload an attachment, promote it into a project KB, and
 * confirm content lands in {@code knowledge_chunks}; plus every failure path
 * in the dispatch table (400 / 403 / 404 / 409).
 */
class AttachmentPromotionControllerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    private record Setup(UUID projectId, UUID conversationId, UUID knowledgeBaseId) {}

    private Setup setup(String slug) {
        Project project = data.project().named("promote-rest-" + slug).create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Promote", null);
        UUID conversationId = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class).getBody().id();
        KnowledgeBase kb = knowledgeBaseService.createProjectBase(
                project.getId(), "kb-" + slug, "Target KB",
                StubRetrievalProvider.PROVIDER_ID, null);
        return new Setup(project.getId(), conversationId, kb.getId());
    }

    private UUID upload(UUID conversationId, byte[] content, String filename, MediaType partType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", adminHeaders().getFirst("Authorization"));
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(partType);
        ByteArrayResource resource = new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, partHeaders));
        ResponseEntity<AttachmentResponse> uploaded = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                AttachmentResponse.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return uploaded.getBody().id();
    }

    private String promoteUrl(UUID projectId, UUID conversationId, UUID attachmentId) {
        return "/api/v1/projects/" + projectId + "/conversations/" + conversationId
                + "/attachments/" + attachmentId + "/promote";
    }

    private HttpHeaders viewerHeaders(UUID projectId) {
        String token = jwtTokenProvider.generateUserAccessToken(
                UUID.randomUUID(), "Viewer", "viewer@test.local",
                List.of("proj:" + projectId + ":VIEWER"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return headers;
    }

    /** Project-scoped EDITOR — the authority promote-to-KB requires. */
    private HttpHeaders editorHeaders(UUID projectId) {
        return userHeaders(TEST_ADMIN_ID, projectId);
    }

    @Test
    void promotesCleanAttachmentAndIngestsChunks() {
        Setup s = setup("happy");
        UUID attachmentId = upload(s.conversationId(),
                "Myrmec ingests promoted attachments into knowledge bases.".getBytes(StandardCharsets.UTF_8),
                "notes.txt", MediaType.TEXT_PLAIN);

        ResponseEntity<PromoteAttachmentResponse> promoted = restTemplate.exchange(
                promoteUrl(s.projectId(), s.conversationId(), attachmentId),
                HttpMethod.POST,
                new HttpEntity<>(new PromoteAttachmentRequest(s.knowledgeBaseId(), null), editorHeaders(s.projectId())),
                PromoteAttachmentResponse.class);

        assertThat(promoted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PromoteAttachmentResponse body = promoted.getBody();
        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo("INGESTING");
        assertThat(body.knowledgeSourceId()).isNotNull();

        // The extracted text landed in knowledge_chunks (closes the loop with #26/#28).
        List<KnowledgeChunk> chunks =
                knowledgeChunkRepository.findByKnowledgeSourceId(body.knowledgeSourceId());
        assertThat(chunks).isNotEmpty();
    }

    @Test
    void imageWithNoExtractableTextIsRejected() {
        Setup s = setup("notext");
        // A 1x1 PNG carries no extractable text.
        byte[] png = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        UUID attachmentId = upload(s.conversationId(), png, "pixel.png", MediaType.IMAGE_PNG);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                promoteUrl(s.projectId(), s.conversationId(), attachmentId),
                HttpMethod.POST,
                new HttpEntity<>(new PromoteAttachmentRequest(s.knowledgeBaseId(), null), editorHeaders(s.projectId())),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void viewerLacksEditorAndIsForbidden() {
        Setup s = setup("forbidden");
        UUID attachmentId = upload(s.conversationId(),
                "content".getBytes(StandardCharsets.UTF_8), "notes.txt", MediaType.TEXT_PLAIN);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                promoteUrl(s.projectId(), s.conversationId(), attachmentId),
                HttpMethod.POST,
                new HttpEntity<>(new PromoteAttachmentRequest(s.knowledgeBaseId(), null), viewerHeaders(s.projectId())),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownKnowledgeBaseIsNotFound() {
        Setup s = setup("unknownkb");
        UUID attachmentId = upload(s.conversationId(),
                "content".getBytes(StandardCharsets.UTF_8), "notes.txt", MediaType.TEXT_PLAIN);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                promoteUrl(s.projectId(), s.conversationId(), attachmentId),
                HttpMethod.POST,
                new HttpEntity<>(new PromoteAttachmentRequest(UUID.randomUUID(), null), editorHeaders(s.projectId())),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("errorCode").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void repeatPromotionIsDuplicate() {
        Setup s = setup("dup");
        UUID attachmentId = upload(s.conversationId(),
                "Repeat me into the KB.".getBytes(StandardCharsets.UTF_8), "notes.txt", MediaType.TEXT_PLAIN);
        HttpHeaders editor = editorHeaders(s.projectId());
        HttpEntity<PromoteAttachmentRequest> request = new HttpEntity<>(
                new PromoteAttachmentRequest(s.knowledgeBaseId(), null), editor);

        ResponseEntity<PromoteAttachmentResponse> first = restTemplate.exchange(
                promoteUrl(s.projectId(), s.conversationId(), attachmentId),
                HttpMethod.POST, request, PromoteAttachmentResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> second = restTemplate.exchange(
                promoteUrl(s.projectId(), s.conversationId(), attachmentId),
                HttpMethod.POST,
                new HttpEntity<>(new PromoteAttachmentRequest(s.knowledgeBaseId(), null), editor),
                JsonNode.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("errorCode").asText()).isEqualTo("DUPLICATE_CODE");
    }
}
