package ai.myrmec.engine.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class RefreshResponse {

    private String accessToken;
    private Instant accessTokenExpiresAt;
}
