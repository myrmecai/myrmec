package ai.myrmec.engine.group.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class GroupMemberResponse {

    private UUID roleId;
    private UUID userId;
    private String email;
    private String name;
    private String role;
}
