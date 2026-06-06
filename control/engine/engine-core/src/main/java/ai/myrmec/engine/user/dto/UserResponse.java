package ai.myrmec.engine.user.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class UserResponse {

    private UUID id;
    private String email;
    private String name;
    private String providerCode;
    private Boolean isActive;
    private Boolean isSystem;
    private Instant createdAt;
    private Instant updatedAt;
    private List<RoleInfo> roles;

    @Data
    @Builder
    public static class RoleInfo {
        private UUID id;
        private String role;
        private UUID projectId;
        private Instant createdAt;
    }
}
