package ai.myrmec.engine.setting;

import ai.myrmec.engine.IntegrationTestBase;
import ai.myrmec.engine.setting.dto.SystemSettingResponse;
import ai.myrmec.engine.setting.dto.UpdateSystemSettingRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #71a &mdash; HTTP surface of the System Settings admin controller plus
 * type validation and the audit side-effect.
 */
class SystemSettingControllerTest extends IntegrationTestBase {

    @Autowired
    private SystemSettingService systemSettingService;

    @Test
    void adminCanListSeededSettings() {
        ResponseEntity<List<SystemSettingResponse>> listed = restTemplate.exchange(
                "/api/v1/admin/system-settings",
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders()),
                new ParameterizedTypeReference<List<SystemSettingResponse>>() {});

        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody())
                .extracting(SystemSettingResponse::key)
                .contains("history_limit", "summary_trigger_ratio", "summarizer_model_code");
    }

    @Test
    void adminCanUpdateAnIntSettingAndAuditIsRecorded() {
        long auditBefore = auditEventRepository.count();

        ResponseEntity<SystemSettingResponse> updated = restTemplate.exchange(
                "/api/v1/admin/system-settings/history_limit",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateSystemSettingRequest("40"), adminHeaders()),
                SystemSettingResponse.class);

        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody()).isNotNull();
        assertThat(updated.getBody().value()).isEqualTo("40");
        assertThat(updated.getBody().updatedBy()).isEqualTo(TEST_ADMIN_ID);

        // Persisted typed read reflects the change.
        assertThat(systemSettingService.getInt("history_limit", 20L)).isEqualTo(40L);

        // One audit row was written under the settings action.
        assertThat(auditEventRepository.count()).isEqualTo(auditBefore + 1);
    }

    @Test
    void blankValueResetsToDefaultAndTypedReadFallsBack() {
        ResponseEntity<SystemSettingResponse> reset = restTemplate.exchange(
                "/api/v1/admin/system-settings/turn_timeout_seconds",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateSystemSettingRequest("   "), adminHeaders()),
                SystemSettingResponse.class);

        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reset.getBody()).isNotNull();
        assertThat(reset.getBody().value()).isEmpty();
        // Blank => typed read returns the caller default.
        assertThat(systemSettingService.getInt("turn_timeout_seconds", 999L)).isEqualTo(999L);
    }

    @Test
    void invalidRatioValueIsRejectedWith400() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/admin/system-settings/summary_trigger_ratio",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateSystemSettingRequest("1.5"), adminHeaders()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void invalidIntValueIsRejectedWith400() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/admin/system-settings/history_limit",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateSystemSettingRequest("not-a-number"), adminHeaders()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void updatingUnknownKeyReturns404() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/admin/system-settings/no_such_key",
                HttpMethod.PUT,
                new HttpEntity<>(new UpdateSystemSettingRequest("x"), adminHeaders()),
                String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
