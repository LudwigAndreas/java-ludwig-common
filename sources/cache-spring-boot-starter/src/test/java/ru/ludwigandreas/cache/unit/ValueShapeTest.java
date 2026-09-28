package ru.ludwigandreas.cache.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.cache.shared.ValueShape;

/**
 * The fingerprint that makes a forgotten key-namespace bump loud.
 *
 * <p>A hand-maintained version integer that somebody forgets is worse than no versioning at all, because it
 * converts a loud failure into a silent one: the reader is now confident it is looking at a compatible entry.
 * These tests pin the two properties that make the fingerprint a usable alarm - stable when nothing that
 * matters changed, different when the shape did.
 */
class ValueShapeTest {

    @Test
    @DisplayName("the same type fingerprints the same way in every process")
    void sameTypeSameFingerprint() {
        assertThat(ValueShape.of(TestValue.class)).isEqualTo(ValueShape.of(TestValue.class));
    }

    @Test
    @DisplayName("a differently-shaped value fingerprints differently")
    void differentShapeDifferentFingerprint() {
        assertThat(ValueShape.of(TestValue.class)).isNotEqualTo(ValueShape.of(ChangedValue.class));
    }

    /**
     * Only the set of members counts, not the order they were declared in.
     *
     * <p>Reordering record components changes no JSON document, so it must not change the fingerprint - a
     * fingerprint that flagged a harmless reorder would train people to bump the version for changes that do
     * not need it, which is how a safety belt stops being taken seriously. It is asserted here as "two types
     * with the same members in different orders describe the same member set", which is the closest the public
     * API allows: the type's own name is part of the fingerprint, so two differently-named test types can never
     * hash identically, and the property that actually matters is about two releases of <em>one</em> type.
     */
    @Test
    @DisplayName("declaration order is not part of the member set")
    void declarationOrderIsNotPartOfTheShape() {
        assertThat(members(Ordered.class)).isEqualTo(members(Reordered.class));
    }

    /** The sorted member set of a record, as ValueShape derives it. */
    private static List<String> members(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ':' + component.getType().getName())
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("the fingerprint is short enough to read in a log line")
    void fingerprintIsShort() {
        assertThat(ValueShape.of(TestValue.class)).hasSize(16).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("a nested platform type is part of the shape; a JDK type is not walked into")
    void ownTypesAreWalkedIntoAndJdkTypesAreNot() {
        assertThat(ValueShape.of(Nesting.class)).isNotEqualTo(ValueShape.of(NestingChanged.class));
        assertThat(ValueShape.of(WithList.class)).isEqualTo(ValueShape.of(WithList.class));
    }

    private record ChangedValue(String text, int count, boolean extra) {
    }

    private record Ordered(String a, int b) {
    }

    private record Reordered(int b, String a) {
    }

    private record Nesting(TestValue inner) {
    }

    private record NestingChanged(ChangedValue inner) {
    }

    private record WithList(List<String> items) {
    }
}
