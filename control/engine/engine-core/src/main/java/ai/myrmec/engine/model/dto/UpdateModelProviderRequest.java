package ai.myrmec.engine.model.dto;

import ai.myrmec.engine.model.DeploymentType;
import ai.myrmec.engine.model.ModelStatus;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Phase 10 #70 &mdash; request body for {@code PUT /api/v1/admin/providers/{code}}.
 *
 * <p>{@code code} is immutable; everything else can be tweaked. Pass
 * {@code null} to leave a field unchanged.</p>
 */
@Value
@Builder
public class UpdateModelProviderRequest {

    @Size(max = 100)
    String name;

    @Size(max = 500)
    String baseUrl;

    DeploymentType deploymentType;

    Boolean requiresAuth;

    @Size(max = 500)
    String docsUrl;

    String description;

    ModelStatus status;

    /**
     * Linked ConnectionConfig holding the provider's credential in the
     * secrets vault. Pass null to leave unchanged; pass UUID to link.
     */
    UUID connectionConfigId;
}
