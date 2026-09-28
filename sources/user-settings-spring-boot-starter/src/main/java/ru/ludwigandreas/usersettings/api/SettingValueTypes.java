package ru.ludwigandreas.usersettings.api;

/**
 * The type discriminators persisted next to every stored value.
 *
 * <p>The discriminator says how the text was <em>encoded</em>, not which class it belongs to. That
 * distinction matters: storing a fully-qualified class name would tie every row to a Java type that
 * a later refactor renames, and would leak the owning service's package layout into a table a
 * different service projects. "This column holds an ISO-8601 instant" survives both.
 *
 * <p>It is checked on read. A row whose discriminator does not match the converter the definition
 * now uses is treated as unreadable and skipped rather than coerced - a value written as a duration
 * and read as a number would otherwise silently become a different setting.
 */
public final class SettingValueTypes {

    public static final String STRING = "string";
    public static final String BOOLEAN = "boolean";
    public static final String INTEGER = "integer";
    public static final String LONG = "long";
    public static final String DOUBLE = "double";
    public static final String DECIMAL = "decimal";
    public static final String INSTANT = "instant";
    public static final String DURATION = "duration";
    public static final String LOCAL_TIME = "local-time";
    public static final String ZONE_ID = "zone-id";
    public static final String LOCALE = "locale";
    public static final String UUID = "uuid";
    /** Any enum, stored as its constant name. */
    public static final String ENUM = "enum";
    /** Anything else, stored as JSON. */
    public static final String JSON = "json";

    private SettingValueTypes() {
    }
}
