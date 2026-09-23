package ru.ludwigandreas.usersettings.exception;

/**
 * The problem and validation codes this module emits.
 *
 * <p>Constants rather than literals because each one is published in the response body: a client
 * branches on {@code code} instead of parsing translated text, which makes every value here part of
 * the API contract. The keys in {@code i18n/ludwig-user-settings-messages.properties} are exactly
 * these strings, each with a {@code .title} sibling.
 *
 * <p>A consuming service's own validator may return any code it likes, including one from its own
 * bundle - these are only the ones the module itself produces.
 */
public final class SettingProblemCodes {

    /** Namespace prefix for every code below. */
    public static final String PREFIX = "ludwig.user-settings.error.";

    /** A definition was referenced that no {@code SettingDefinitionSource} registered. */
    public static final String UNKNOWN_SETTING = PREFIX + "unknown-setting";
    /** A write was attempted against a definition whose {@code userEditable} flag is false. */
    public static final String NOT_EDITABLE = PREFIX + "not-editable";
    /** The caller may only read or write their own settings, and this was someone else's. */
    public static final String ACCESS_DENIED = PREFIX + "access-denied";
    /** No tenant could be determined, so the lookup was refused rather than run unscoped. */
    public static final String TENANT_UNRESOLVABLE = PREFIX + "tenant-unresolvable";
    /** A stored value could not be converted back to the definition's type. */
    public static final String CONVERSION = PREFIX + "conversion";
    /** Writes are not available: this service runs the module in projection mode. */
    public static final String READ_ONLY = PREFIX + "read-only";
    /** A definition was used at runtime that does not match the one registered under its key. */
    public static final String CONFIGURATION = PREFIX + "configuration";

    /**
     * Namespace for the codes the built-in validators return.
     *
     * <p>A validation failure is rendered under the violation's own code rather than under one
     * generic "validation failed" code - see {@code SettingValidationException} - so each of these is
     * a problem code in its own right and has a bundle entry and a title.
     */
    public static final String VALIDATION_PREFIX = "ludwig.user-settings.validation.";

    /** A value is required and none was given. */
    public static final String VALIDATION_REQUIRED = VALIDATION_PREFIX + "required";
    /** The value is outside the permitted set. */
    public static final String VALIDATION_ONE_OF = VALIDATION_PREFIX + "one-of";
    /** The value is outside the permitted range. */
    public static final String VALIDATION_RANGE = VALIDATION_PREFIX + "range";
    /** The value is longer than the permitted length. */
    public static final String VALIDATION_MAX_LENGTH = VALIDATION_PREFIX + "max-length";
    /** The value does not match the required pattern. */
    public static final String VALIDATION_PATTERN = VALIDATION_PREFIX + "pattern";

    private SettingProblemCodes() {
    }
}
