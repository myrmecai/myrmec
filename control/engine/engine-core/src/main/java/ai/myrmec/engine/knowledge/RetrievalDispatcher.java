// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves {@link RetrievalProvider} instances by their stable string id.
 *
 * <p>Startup validation fails fast if two beans declare the same id.</p>
 */
@Slf4j
@Component
public class RetrievalDispatcher {

    private final List<RetrievalProvider> providers;
    private Map<String, RetrievalProvider> registry;

    public RetrievalDispatcher(List<RetrievalProvider> providers) {
        this.providers = providers;
    }

    @PostConstruct
    void validateAndIndex() {
        registry = providers.stream()
                .collect(Collectors.toMap(
                        RetrievalProvider::id,
                        Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException(
                                    "Duplicate RetrievalProvider id '" + a.id()
                                            + "'. Providers must have globally unique ids.");
                        }));
        log.info("Registered {} retrieval provider(s): {}",
                registry.size(), registry.keySet());
    }

    /**
     * Resolve a provider by id.
     *
     * @throws RetrievalProviderNotFoundException if no provider matches.
     */
    public RetrievalProvider resolve(String providerId) {
        RetrievalProvider provider = registry.get(providerId);
        if (provider == null) {
            throw new RetrievalProviderNotFoundException(providerId);
        }
        return provider;
    }
}