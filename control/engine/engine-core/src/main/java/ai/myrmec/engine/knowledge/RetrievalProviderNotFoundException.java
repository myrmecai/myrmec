// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when {@link RetrievalDispatcher} cannot resolve a provider for the
 * configured provider id.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RetrievalProviderNotFoundException extends RuntimeException {

    public RetrievalProviderNotFoundException(String providerId) {
        super("No retrieval provider registered with id: " + providerId);
    }
}