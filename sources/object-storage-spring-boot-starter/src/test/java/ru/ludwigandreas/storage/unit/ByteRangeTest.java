package ru.ludwigandreas.storage.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.storage.api.ByteRange;

/**
 * The inclusive/exclusive boundary is the one place in this module where an off-by-one produces a
 * single duplicated or missing record in an otherwise clean import, so the conversion is pinned here
 * rather than left to be exercised incidentally by the store tests.
 */
class ByteRangeTest {

    @Test
    void rendersTheInclusiveHeaderFormHttpUses() {
        assertThat(ByteRange.of(0, 9).toHeaderValue()).isEqualTo("bytes=0-9");
        assertThat(ByteRange.from(800).toHeaderValue()).isEqualTo("bytes=800-");
    }

    @Test
    void convertsAnExclusiveLengthIntoAnInclusiveEnd() {
        ByteRange firstTen = ByteRange.ofLength(0, 10);

        assertThat(firstTen.endInclusive()).isEqualTo(9);
        assertThat(firstTen.length()).isEqualTo(10);
        assertThat(firstTen.toHeaderValue()).isEqualTo("bytes=0-9");
    }

    @Test
    void knowsWhetherItNamesItsOwnEnd() {
        assertThat(ByteRange.of(0, 9).bounded()).isTrue();
        assertThat(ByteRange.from(0).bounded()).isFalse();
    }

    @Test
    void refusesToInventALengthForARangeThatRunsToTheEndOfTheObject() {
        assertThatThrownBy(() -> ByteRange.from(0).length())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWindowsThatCannotExist() {
        assertThatThrownBy(() -> ByteRange.of(-1, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ByteRange.of(10, 4)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ByteRange.ofLength(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ByteRange.ofLength(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsARangeThatStartsBeyondAnyPlausibleObject() {
        // Not validated against a size, because the size is not known here and a second round trip to
        // find it out would be both slower and racy. The store answers such a range with an empty
        // stream; see ObjectStore#open(String, ByteRange).
        assertThat(ByteRange.from(Long.MAX_VALUE).start()).isEqualTo(Long.MAX_VALUE);
    }
}
