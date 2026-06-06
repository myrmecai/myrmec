package ai.myrmec.engine.user.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ExternalAuthStartResponse {

    private String providerCode;
    private String state;
    private String authorizationUrl;
}
