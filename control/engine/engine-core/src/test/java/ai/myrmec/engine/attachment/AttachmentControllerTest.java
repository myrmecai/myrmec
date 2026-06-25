package ai.myrmec.engine.attachment;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.attachment.dto.AttachmentResponse;
import ai.myrmec.engine.conversation.dto.ConversationResponse;
import ai.myrmec.engine.conversation.dto.ConversationMessageResponse;
import ai.myrmec.engine.conversation.dto.CreateConversationRequest;
import ai.myrmec.engine.project.Project;
import ai.myrmec.engine.project.ProjectService;
import ai.myrmec.engine.project.dto.UpdateProjectRequest;
import ai.myrmec.engine.testing.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
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
 * Integration test for the attachment upload pipeline (#103) and the scan
 * gate (#104). Exercises clean upload + download + list + delete, EICAR
 * quarantine, and MIME allowlist rejection through the real HTTP surface.
 */
class AttachmentControllerTest extends IntegrationTestBase {

    @Autowired
    private TestDataBuilder data;

        @Autowired
        private ProjectService projectService;

    private UUID newConversation(String name) {
        Project project = data.project().named(name).create();
        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Attachments", null);
        return restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class).getBody().id();
    }

    private HttpEntity<MultiValueMap<String, Object>> multipart(
            byte[] content, String filename, MediaType partType) {
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
        return new HttpEntity<>(body, headers);
    }

    @Test
    void cleanTextAttachmentUploadsListsDownloadsAndDeletes() {
        UUID conversationId = newConversation("attach-clean");
        byte[] content = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<AttachmentResponse> uploaded = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.POST,
                multipart(content, "notes.txt", MediaType.TEXT_PLAIN),
                AttachmentResponse.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AttachmentResponse body = uploaded.getBody();
        assertThat(body).isNotNull();
        assertThat(body.scanStatus()).isEqualTo("CLEAN");
        assertThat(body.filename()).isEqualTo("notes.txt");
        assertThat(body.mediaType()).isEqualTo("text/plain");
        assertThat(body.sizeBytes()).isEqualTo(content.length);
        assertThat(body.sha256()).isNotBlank();
        UUID attachmentId = body.id();

        // List shows the one attachment.
        ResponseEntity<List<AttachmentResponse>> listed = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AttachmentResponse>>() {});
        assertThat(listed.getBody()).hasSize(1);

        // Download returns the original bytes.
        ResponseEntity<byte[]> content2 = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments/"
                        + attachmentId + "/content",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                byte[].class);
        assertThat(content2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content2.getBody()).isEqualTo(content);

        // Delete removes it.
        ResponseEntity<Void> deleted = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments/" + attachmentId,
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders()),
                Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<List<AttachmentResponse>> afterDelete = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AttachmentResponse>>() {});
        assertThat(afterDelete.getBody()).isEmpty();
    }

    @Test
    void malwareExecutableIsQuarantinedAndRejected() {
        UUID conversationId = newConversation("attach-malware");

        // A fake PE/DOS executable (MZ magic) disguised under an allowed MIME.
        // We deliberately avoid the real EICAR signature here: host antivirus
        // (e.g. Windows Defender) quarantines the multipart temp file at the
        // filesystem layer before the app can read it, which is an environment
        // artifact rather than an app behaviour. EICAR detection itself is
        // covered in-memory by BundledContentScanProviderTest.
        byte[] fakeExecutable = new byte[]{'M', 'Z', 0x10, 0x20, 0x30, 0x40};

        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.POST,
                multipart(fakeExecutable, "report.pdf", MediaType.APPLICATION_PDF),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // The quarantined row is retained (transparency) with INFECTED status
        // and no downloadable bytes.
        ResponseEntity<List<AttachmentResponse>> listed = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AttachmentResponse>>() {});
        assertThat(listed.getBody()).hasSize(1);
        AttachmentResponse quarantined = listed.getBody().get(0);
        assertThat(quarantined.scanStatus()).isEqualTo("INFECTED");
        assertThat(quarantined.scanThreat()).isEqualTo("Executable.PE-DOS");
        assertThat(quarantined.sha256()).isNull();
    }

    @Test
    void disallowedMediaTypeIsRejected() {
        UUID conversationId = newConversation("attach-badtype");

        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.POST,
                multipart("MZbinary".getBytes(StandardCharsets.US_ASCII), "app.bin",
                        MediaType.APPLICATION_OCTET_STREAM),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<List<AttachmentResponse>> listed = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AttachmentResponse>>() {});
        assertThat(listed.getBody()).isEmpty();
    }

    @Test
    void postingUserMessageBindsCleanUnboundAttachments() {
        UUID conversationId = newConversation("attach-bind");
        byte[] content = "context document".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<AttachmentResponse> uploaded = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.POST,
                multipart(content, "doc.txt", MediaType.TEXT_PLAIN),
                AttachmentResponse.class);
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID attachmentId = uploaded.getBody().id();
        assertThat(uploaded.getBody().messageId()).isNull();

        // Post a user message; the dispatcher may fail (no agent) but binding
        // happens before dispatch and is unaffected.
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        jsonHeaders.set("Authorization", adminHeaders().getFirst("Authorization"));
        ResponseEntity<ConversationMessageResponse> posted = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/messages",
                HttpMethod.POST,
                new HttpEntity<>("{\"content\":\"see attached\"}", jsonHeaders),
                ConversationMessageResponse.class);
        assertThat(posted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID messageId = posted.getBody().id();

        // The attachment is now bound to that message.
        ResponseEntity<List<AttachmentResponse>> listed = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<AttachmentResponse>>() {});
        assertThat(listed.getBody()).hasSize(1);
        AttachmentResponse bound = listed.getBody().get(0);
        assertThat(bound.id()).isEqualTo(attachmentId);
        assertThat(bound.messageId()).isEqualTo(messageId);
    }

    @Test
    void uploadRejectedWhenProjectAttachmentsAreDisabled() {
        Project project = data.project().named("attach-disabled").create();
        UpdateProjectRequest disable = new UpdateProjectRequest();
        disable.setAttachmentsEnabled(false);
        projectService.update(project.getId(), disable);

        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Attachments disabled", null);
        UUID conversationId = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class).getBody().id();

        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.POST,
                multipart("text".getBytes(StandardCharsets.UTF_8),
                        "notes.txt", MediaType.TEXT_PLAIN),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).contains("ATTACHMENTS_DISABLED");
    }

    @Test
    void uploadUsesProjectMaxFileSizeWhenConfigured() {
        Project project = data.project().named("attach-max-size").create();
        UpdateProjectRequest limit = new UpdateProjectRequest();
        limit.setAttachmentMaxFileSizeBytes(5L);
        projectService.update(project.getId(), limit);

        CreateConversationRequest body = new CreateConversationRequest(
                project.getId(), null, "Attachments max size", null);
        UUID conversationId = restTemplate.exchange(
                "/api/v1/conversations",
                HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()),
                ConversationResponse.class).getBody().id();

        byte[] tooLarge = "123456".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> rejected = restTemplate.exchange(
                "/api/v1/conversations/" + conversationId + "/attachments",
                HttpMethod.POST,
                multipart(tooLarge, "tiny.txt", MediaType.TEXT_PLAIN),
                String.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rejected.getBody()).contains("TOO_LARGE");
    }
}