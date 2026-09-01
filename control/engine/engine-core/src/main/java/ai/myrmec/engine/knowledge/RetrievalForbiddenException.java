// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when an agent attempts to retrieve a knowledge source that is not
 * pinned to its active session (§8 scope check).
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class RetrievalForbiddenException extends RuntimeException {

    public RetrievalForbiddenException(String message) {
        super(message);
    }
}