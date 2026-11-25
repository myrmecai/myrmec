// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Seed DTO for a model, read from
 * {@code seed-data/optional/models.json}. Mirrors the {@code Model} entity
 * fields plus a {@code capabilities} child list bound via
 * {@code model_capability_bindings}.
 *
 * <p>The {@code modelId} field is the correlation key: it must match the
 * {@code MYRMEC_TEST_*_MODEL_ID} value in the environment.</p>
 */
public record ModelSeed(
        String code,
        String name,
        String provider,
        String modelId,
        String apiEndpoint,
        Map<String, Object> infraConfig,
        Map<String, Object> defaultParams,
        BigDecimal inputPrice,
        BigDecimal outputPrice,
        String currency,
        Integer contextWindowTokens,
        Integer maxOutputTokens,
        LocalDate trainingCutoffDate,
        LocalDate releaseDate,
        List<ModelCapabilitySeed> capabilities) {

    /** One capability binding: a catalog code plus optional parameters. */
    public record ModelCapabilitySeed(
            String code,
            Map<String, Object> parameters) {
    }
}
