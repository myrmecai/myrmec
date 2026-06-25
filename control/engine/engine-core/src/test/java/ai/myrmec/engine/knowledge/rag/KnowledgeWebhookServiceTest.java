package ai.myrmec.engine.knowledge.rag;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine._system.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the event-driven (push) re-sync path (#25a):
 * {@link KnowledgeWebhookService} (HMAC verification + enqueue) and the public
 * {@link KnowledgeWebhookController} endpoint. Not {@code @Transactional} — the
 * end-to-end HTTP test needs committed data visible to the server thread (base
 * class cleans knowledge tables before each test).
 */
class KnowledgeWebhookServiceTest extends IntegrationTestBase {

    @Autowired
    private KnowledgeWebhookService webhookService;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    private static final String SECRET = "top-secret-webhook-key";
    private static final byte[] BODY = "{\"event\":\"page.updated\",\"id\":\"42\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void validSignatureMarksSourceDue() {
        KnowledgeSource source = newSource("{\"webhookSecret\":\"" + SECRET + "\"}", true);

        KnowledgeWebhookService.Outcome outcome =
                webhookService.requestResync(source.getId(), BODY, sign(SECRET, BODY));

        assertThat(outcome).isEqualTo(KnowledgeWebhookService.Outcome.ENQUEUED);
        KnowledgeSource reloaded = knowledgeSourceRepository.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getSyncRequestedAt()).isNotNull();
    }

    @Test
    void invalidSignatureIsRejectedAndSourceNotMarked() {
        KnowledgeSource source = newSource("{\"webhookSecret\":\"" + SECRET + "\"}", true);

        KnowledgeWebhookService.Outcome outcome =
                webhookService.requestResync(source.getId(), BODY, sign("wrong-secret", BODY));

        assertThat(outcome).isEqualTo(KnowledgeWebhookService.Outcome.INVALID_SIGNATURE);
        assertThat(knowledgeSourceRepository.findById(source.getId()).orElseThrow().getSyncRequestedAt()).isNull();
    }

    @Test
    void missingSignatureIsRejected() {
        KnowledgeSource source = newSource("{\"webhookSecret\":\"" + SECRET + "\"}", true);

        KnowledgeWebhookService.Outcome outcome =
                webhookService.requestResync(source.getId(), BODY, null);

        assertThat(outcome).isEqualTo(KnowledgeWebhookService.Outcome.INVALID_SIGNATURE);
    }

    @Test
    void sourceWithoutWebhookSecretIsNotConfigured() {
        KnowledgeSource source = newSource("{\"spaceKey\":\"ENG\"}", true);

        KnowledgeWebhookService.Outcome outcome =
                webhookService.requestResync(source.getId(), BODY, sign(SECRET, BODY));

        assertThat(outcome).isEqualTo(KnowledgeWebhookService.Outcome.NOT_CONFIGURED);
    }

    @Test
    void disabledSourceIsAcceptedButNotQueued() {
        KnowledgeSource source = newSource("{\"webhookSecret\":\"" + SECRET + "\"}", false);

        KnowledgeWebhookService.Outcome outcome =
                webhookService.requestResync(source.getId(), BODY, sign(SECRET, BODY));

        assertThat(outcome).isEqualTo(KnowledgeWebhookService.Outcome.DISABLED);
        assertThat(knowledgeSourceRepository.findById(source.getId()).orElseThrow().getSyncRequestedAt()).isNull();
    }

    @Test
    void unknownSourceThrowsResourceNotFound() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> webhookService.requestResync(missing, BODY, sign(SECRET, BODY)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void webhookEndpointIsPublicAndEnqueuesOnValidSignature() {
        KnowledgeSource source = newSource("{\"webhookSecret\":\"" + SECRET + "\"}", true);
        String payload = "{\"event\":\"updated\"}";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(KnowledgeWebhookController.SIGNATURE_HEADER, sign(SECRET, payloadBytes));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/knowledge/webhooks/" + source.getId(),
                new HttpEntity<>(payload, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("ENQUEUED");
        assertThat(knowledgeSourceRepository.findById(source.getId()).orElseThrow().getSyncRequestedAt()).isNotNull();
    }

    @Test
    void webhookEndpointRejectsBadSignatureWith401() {
        KnowledgeSource source = newSource("{\"webhookSecret\":\"" + SECRET + "\"}", true);
        String payload = "{\"event\":\"updated\"}";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(KnowledgeWebhookController.SIGNATURE_HEADER, "sha256=deadbeef");

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/knowledge/webhooks/" + source.getId(),
                new HttpEntity<>(payload, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(knowledgeSourceRepository.findById(source.getId()).orElseThrow().getSyncRequestedAt()).isNull();
    }

    // --- helpers -------------------------------------------------------------

    private KnowledgeSource newSource(String configJson, boolean enabled) {
        KnowledgeBase kb = knowledgeBaseService.createSystemBase(
                "wh-kb-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                StubRetrievalProvider.PROVIDER_ID,
                null);
        KnowledgeSource source = knowledgeBaseService.addSource(
                kb.getId(),
                WebCrawlConnector.CONNECTOR_TYPE,
                "src",
                "https://example.com/",
                configJson,
                null);
        if (!enabled) {
            source.setEnabled(false);
            knowledgeSourceRepository.save(source);
        }
        return source;
    }

    private static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
