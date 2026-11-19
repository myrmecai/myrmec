// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors

package ai.myrmec.engine.spi.quota;

/** Quota scope tiers: org &rarr; group &rarr; Team &rarr; service. */
public enum QuotaScope {
    ORG,
    GROUP,
    TEAM,
    SERVICE
}
