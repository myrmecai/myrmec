package ai.myrmec.engine.setting;

/**
 * #71a &mdash; the value contract for a {@link SystemSetting} row.
 *
 * <p>Drives parsing and validation in {@link SystemSettingService}: the
 * raw {@code value} is always stored as text and interpreted according
 * to this type. A blank value always means "unset &mdash; use the
 * code-side default".</p>
 */
public enum SettingType {

    /** Free text. */
    STRING,

    /** A parseable {@code long}. */
    INT,

    /** A {@code double} constrained to the closed interval [0, 1]. */
    RATIO,

    /** {@code true} / {@code false} (case-insensitive). */
    BOOL,

    /** A model code string; empty means "fall back to the caller default". */
    MODEL_REF,

    /** Any well-formed JSON document. */
    JSON
}
