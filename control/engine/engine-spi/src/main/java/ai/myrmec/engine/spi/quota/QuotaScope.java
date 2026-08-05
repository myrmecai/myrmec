// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.spi.quota;

/** Quota scope tiers: ORG &rarr; GROUP &rarr; PROJECT &rarr; SERVICE. */
public enum QuotaScope {
    ORG,
    GROUP,
    PROJECT,
    SERVICE
}
