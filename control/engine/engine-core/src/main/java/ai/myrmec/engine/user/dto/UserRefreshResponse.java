package ai.myrmec.engine.user.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
public class UserRefreshResponse {

    private String accessToken;
    private Instant accessTokenExpiresAt;
    private List<String> roles;
}
