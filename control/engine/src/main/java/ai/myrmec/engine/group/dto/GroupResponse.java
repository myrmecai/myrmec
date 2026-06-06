package ai.myrmec.engine.group.dto;

import ai.myrmec.engine.group.Group;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class GroupResponse {

    private UUID id;
    private String name;
    private String description;
    private UUID parentGroupId;
    private Instant createdAt;
    private Instant updatedAt;

    public static GroupResponse from(Group group) {
        return GroupResponse.builder()
                .id(group.getId())
                .name(group.getName())
                .description(group.getDescription())
                .parentGroupId(group.getParentGroupId())
                .createdAt(group.getCreatedAt())
                .updatedAt(group.getUpdatedAt())
                .build();
    }
}
