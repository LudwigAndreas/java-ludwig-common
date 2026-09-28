package ru.ludwigandreas.ingest.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.ingest.bulk.CopyStagingWriter;
import ru.ludwigandreas.ingest.bulk.JdbcBatchStagingWriter;
import ru.ludwigandreas.ingest.exception.IngestException;

/**
 * The {@code COPY} text format's escaping, and the identifier check that guards the interpolation.
 *
 * <p>Getting the escaping wrong does not produce an error - it produces a row split in the wrong
 * place, which arrives in the target as corrupt data and looks like a bad source file. There is no
 * library to defer to here: the driver's {@code CopyManager} takes bytes.
 */
class CopyEscapingTest {

    @Test
    void escapesTheBackslashFirst() {
        // Escaping the backslash after introducing the others would turn the backslash of "\t" into
        // "\\t", and the row would arrive with a literal backslash-t instead of a tab.
        assertThat(CopyStagingWriter.escape("a\\b")).isEqualTo("a\\\\b");
        assertThat(CopyStagingWriter.escape("a\tb")).isEqualTo("a\\tb");
        assertThat(CopyStagingWriter.escape("a\\\tb")).isEqualTo("a\\\\\\tb");
    }

    @Test
    void escapesEveryCharacterTheFormatGivesMeaningTo() {
        assertThat(CopyStagingWriter.escape("line1\nline2")).isEqualTo("line1\\nline2");
        assertThat(CopyStagingWriter.escape("line1\r\nline2")).isEqualTo("line1\\r\\nline2");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        assertThat(CopyStagingWriter.escape("widget, 12.50 \"large\""))
                .isEqualTo("widget, 12.50 \"large\"");
    }

    @Test
    void acceptsPlainIdentifiers() {
        assertThat(JdbcBatchStagingWriter.requireIdentifier("staging_catalogue"))
                .isEqualTo("staging_catalogue");
        assertThat(JdbcBatchStagingWriter.requireIdentifier("_x9$")).isEqualTo("_x9$");
    }

    @Test
    void refusesAnythingThatCouldNotSafelyBeInterpolated() {
        // Identifiers are interpolated because no SQL dialect lets a placeholder stand for one. They
        // come from the author's own applier rather than from a request today, and this check is here
        // because a component that concatenates identifiers into SQL is exactly the one that later
        // receives a configured name.
        for (String bad : new String[] {"a b", "a;DROP TABLE x", "\"quoted\"", "a-b", "", "1abc", null}) {
            assertThatThrownBy(() -> JdbcBatchStagingWriter.requireIdentifier(bad))
                    .isInstanceOf(IngestException.class);
        }
    }
}
