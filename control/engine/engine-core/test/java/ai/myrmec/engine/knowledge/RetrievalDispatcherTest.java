// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import ai.myrmec.engine.spi.retrieval.RetrievalException;
import ai.myrmec.engine.spi.retrieval.RetrievalProvider;
import ai.myrmec.engine.spi.retrieval.RetrievalQuery;
import ai.myrmec.engine.spi.retrieval.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RetrievalDispatcher}.
 *
 * <p>Verifies provider registration, duplicate-id detection, and
 * resolve-by-id semantics including the not-found case.</p>
 */
class RetrievalDispatcherTest {

    @Test
    void resolve_returnsProviderForKnownId() {
        RetrievalProvider provider = new FakeProvider("foo");
        RetrievalDispatcher dispatcher = new RetrievalDispatcher(List.of(provider));
        dispatcher.validateAndIndex();

        assertThat(dispatcher.resolve("foo")).isSameAs(provider);
    }

    @Test
    void resolve_throwsForUnknownId() {
        RetrievalDispatcher dispatcher = new RetrievalDispatcher(List.of());
        dispatcher.validateAndIndex();

        assertThatThrownBy(() -> dispatcher.resolve("nope"))
                .isInstanceOf(RetrievalProviderNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void validateAndIndex_failsOnDuplicateIds() {
        RetrievalProvider a = new FakeProvider("dup");
        RetrievalProvider b = new FakeProvider("dup");

        assertThatThrownBy(() -> new RetrievalDispatcher(List.of(a, b)).validateAndIndex())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate RetrievalProvider id 'dup'");
    }

    @Test
    void resolve_isCaseSensitive() {
        RetrievalProvider provider = new FakeProvider("MyProvider");
        RetrievalDispatcher dispatcher = new RetrievalDispatcher(List.of(provider));
        dispatcher.validateAndIndex();

        assertThatThrownBy(() -> dispatcher.resolve("myprovider"))
                .isInstanceOf(RetrievalProviderNotFoundException.class);
    }

    // ---- helper -------------------------------------------------------

    private record FakeProvider(String id) implements RetrievalProvider {
        @Override
        public List<RetrievalResult> query(RetrievalQuery query) throws RetrievalException {
            return List.of();
        }
    }
}