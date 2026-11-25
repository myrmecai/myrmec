// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import ai.myrmec.engine.model.DeploymentType;

/**
 * Seed DTO for a model provider, read from
 * {@code seed-data/optional/model-providers.json}.
 *
 * <p>{@code baseUrl} is the provider's default endpoint; it is stored at the
 * provider level (and on the provider's connection config). A per-model
 * endpoint may still override it at the model level.</p>
 *
 * @param code            stable natural key (e.g. {@code openai})
 * @param name            display name
 * @param baseUrl         provider default base endpoint (nullable)
 * @param deploymentType  {@code CLOUD} or {@code ON_PREMISE}
 * @param requiresAuth    whether the provider requires credentials
 * @param docsUrl         API documentation URL
 * @param description     free-text description
 */
public record ModelProviderSeed(
        String code,
        String name,
        String baseUrl,
        DeploymentType deploymentType,
        boolean requiresAuth,
        String docsUrl,
        String description) {
}
