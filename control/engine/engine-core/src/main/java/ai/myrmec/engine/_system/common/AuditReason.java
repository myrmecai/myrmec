// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 The Myrmec Authors
package ai.myrmec.engine._system.common;

/**
 * Audit reason codes — the {@code reason_code} column in
 * {@code audit_events}. Explains <em>why</em> an action was taken,
 * orthogonal to <em>what</em> action was taken (see {@link DomainConstants.AuditAction}).
 *
 * <p>Most audit events have a {@code null} reason code — the action
 * itself is self-explanatory. Reason codes are used when the same
 * action can be triggered for different administrative causes
 * (e.g. {@code DISABLED} by admin vs. disabled by governance).</p>
 *
 * <p>The UI mirrors these in {@code ui/src/lib/domain-constants.ts}.</p>
 */
public final class AuditReason {

    private AuditReason() {}

    /** Entity was disabled by an admin via the lifecycle UI. */
    public static final String ADMIN_DISABLED = "ADMIN_DISABLED";
    /** Entity was re-enabled by an admin via the lifecycle UI. */
    public static final String ADMIN_REENABLED = "ADMIN_REENABLED";
    /** Entity was archived by an admin via the lifecycle UI. */
    public static final String ADMIN_ARCHIVED = "ADMIN_ARCHIVED";
    /** Entity was un-archived by an admin (restored from ARCHIVED). */
    public static final String ADMIN_UNARCHIVED = "ADMIN_UNARCHIVED";
    /** Project configuration was changed (e.g. agent profile, model). */
    public static final String CONFIG_CHANGED = "CONFIG_CHANGED";
    /** A system or project setting value was changed. */
    public static final String SETTING_CHANGED = "SETTING_CHANGED";
}