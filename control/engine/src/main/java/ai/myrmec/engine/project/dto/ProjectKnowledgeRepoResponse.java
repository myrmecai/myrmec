package ai.myrmec.engine.project.dto;

import ai.myrmec.engine.project.ProjectKnowledgeRepo;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response DTO for project knowledge repo entries.
 */
public record ProjectKnowledgeRepoResponse(
        UUID id,
        UUID projectId,
        String name,
        String repoUrl,
        String branch,
        List<String> instructionPaths,
        UUID credentialSecretId,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProjectKnowledgeRepoResponse from(ProjectKnowledgeRepo entity) {
        return new ProjectKnowledgeRepoResponse(
                entity.getId(),
                entity.getProject().getId(),
                entity.getName(),
                entity.getRepoUrl(),
                entity.getBranch(),
                entity.getInstructionPaths(),
                entity.getCredentialSecretId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
