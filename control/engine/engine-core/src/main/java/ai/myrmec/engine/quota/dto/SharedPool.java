// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.quota.dto;

import lombok.Builder;
import lombok.Value;

/** Read-model for the unreserved shared pool of a project budget. */
@Value
@Builder
public class SharedPool {
    long totalLimit;
    long reservedAmount;
    long sharedAmount;
    long consumedInShared;
}
