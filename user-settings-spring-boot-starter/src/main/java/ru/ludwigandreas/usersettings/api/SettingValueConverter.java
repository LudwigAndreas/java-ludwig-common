package ru.ludwigandreas.usersettings.api;

/**
 * Turns a setting's typed value into the opaque text that is persisted, and back.
 *
 * <p>This is the seam that lets one storage schema serve every platform's settings without becoming
 * an entity-attribute-value store. The table holds text and a discriminator; the type lives in the
 * {@code SettingDefinition}, which is a compile-time artifact of the consuming service. The trade is
 * that nothing can join, aggregate or index on a value - and that trade is acceptable here only
 * because nothing needs to. Do not reach for this pattern for data anyone reports on.
 *
 * <p>Implementations must be stateless and thread-safe: one instance serves every lookup in the
 * process.
 *
 * @param <T> the setting's value type
 */
public interface SettingValueConverter<T> {

    /** The type this converter handles. Matched exactly, not by assignability. */
    Class<T> type();

    /** The discriminator persisted alongside the value; see {@link SettingValueTypes}. */
    String typeId();

    /**
     * Encodes a value for storage.
     *
     * @param value never {@code null} - an absent value is represented by the absence of a row, not
     *              by a row holding null, so that "explicitly set to nothing" and "never set" do not
     *              become the same state
     */
    String toStorage(T value);

    /**
     * Decodes a stored value.
     *
     * @param raw the text previously produced by {@link #toStorage}
     * @throws RuntimeException if {@code raw} cannot be parsed; callers on the read path catch this
     *                          and fall through to the next layer rather than failing the lookup
     */
    T fromStorage(String raw);
}
