package ru.ludwigandreas.jira.field;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import java.util.Objects;

/**
 * A field id paired with the Java type its value decodes to.
 *
 * <p>This is what makes custom field access type-safe. A constant declared once -
 *
 * <pre>{@code
 * public static final CustomField<Double> STORY_POINTS = CustomField.of("customfield_10004", Double.class);
 * }</pre>
 *
 * <p>- turns {@code issue.fields().raw("customfield_10004")} and a manual cast into
 * {@code fieldAccess.read(issue, STORY_POINTS)} returning an {@code Optional<Double>}, with the cast
 * checked by the compiler and the field id written down in exactly one place.
 *
 * <p>Resolve the id from a display name once at startup with
 * {@link CustomFieldRegistry#field(String, Class)} rather than hard-coding it: a custom field id is
 * instance-specific, so a constant baked into application code is a constant that is wrong in every
 * environment but the one it was read from.
 *
 * @param <T> the type the field's value decodes to
 */
public final class CustomField<T> {

    private final String id;
    private final String displayName;
    private final JavaType type;

    private CustomField(String id, String displayName, JavaType type) {
        this.id = Objects.requireNonNull(id, "id");
        this.displayName = displayName;
        this.type = Objects.requireNonNull(type, "type");
    }

    /** A field of a simple type. */
    public static <T> CustomField<T> of(String fieldId, Class<T> type) {
        return new CustomField<>(fieldId, null, TypeFactory.defaultInstance().constructType(type));
    }

    /** A field of a generic type, for example {@code new TypeReference<List<CustomFieldOption>>() {}}. */
    public static <T> CustomField<T> of(String fieldId, TypeReference<T> type) {
        return new CustomField<>(fieldId, null, TypeFactory.defaultInstance().constructType(type));
    }

    /** A field of a resolved Jackson type, carrying the display name it was resolved from. */
    public static <T> CustomField<T> of(String fieldId, String displayName, JavaType type) {
        return new CustomField<>(fieldId, displayName, type);
    }

    /** The API id, for example {@code customfield_10004}. */
    public String id() {
        return id;
    }

    /** The display name this field was resolved from, or {@code null} when it was declared by id. */
    public String displayName() {
        return displayName;
    }

    /** The type the value decodes to. */
    public JavaType type() {
        return type;
    }

    @Override
    public String toString() {
        return displayName == null ? id : displayName + " (" + id + ")";
    }
}
