package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.ludwigandreas.fileaction.format.xlsx.write.FormulaGuard;

/**
 * Values written back into a file somebody will open are not formulas.
 *
 * <p>The values in a reject report came out of a user's own upload and are opened by somebody else - usually
 * whoever administers the import - which is what makes this an injection rather than a curiosity.
 */
class FormulaGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"=SUM(A1)", "+1+1", "-1+1", "@SUM(A1)",
                            "=HYPERLINK(\"http://example.invalid\",\"click\")",
                            "=cmd|'/c calc'!A0"})
    @DisplayName("a value a spreadsheet would evaluate is prefixed so it reads as text")
    void neutralisesFormulaStarters(String dangerous) {
        assertThat(FormulaGuard.neutralise(dangerous)).isEqualTo("'" + dangerous);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\t=cmd|'/c calc'!A0", " =SUM(A1)", "\r\n=SUM(A1)"})
    @DisplayName("leading whitespace does not hide a formula starter, because a spreadsheet skips it too")
    void looksPastLeadingWhitespace(String dangerous) {
        assertThat(FormulaGuard.neutralise(dangerous))
                .as("a guard that only checked charAt(0) would pass this straight through")
                .startsWith("'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A-1", "ordinary text", "3.14", "a=b", "x+y", "'already quoted"})
    @DisplayName("an ordinary value is written unchanged")
    void leavesSafeValuesAlone(String safe) {
        assertThat(FormulaGuard.neutralise(safe)).isEqualTo(safe);
    }

    @Test
    @DisplayName("null and empty pass through rather than becoming a lone apostrophe")
    void handlesNullAndEmpty() {
        assertThat(FormulaGuard.neutralise(null)).isNull();
        assertThat(FormulaGuard.neutralise("")).isEmpty();
    }

    @Test
    @DisplayName("whitespace only passes through")
    void handlesWhitespaceOnly() {
        assertThat(FormulaGuard.neutralise("   ")).isEqualTo("   ");
    }
}
