package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.api.FileScanner;
import ru.ludwigandreas.fileaction.api.ScanOutcome;

/** The scanner seam: what an implementation must say, and what the module ships (nothing). */
class FileScannerContractTest {

    @Test
    @DisplayName("an unsafe outcome must name what was found")
    void unsafeOutcomeNeedsASignature() {
        assertThatThrownBy(() -> new ScanOutcome(false, null, "test-scanner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must name what was found");
    }

    @Test
    @DisplayName("a safe outcome needs no signature")
    void safeOutcomeNeedsNoSignature() {
        ScanOutcome outcome = ScanOutcome.safe("test-scanner");

        assertThat(outcome.safe()).isTrue();
        assertThat(outcome.signature()).isNull();
        assertThat(outcome.scanner()).isEqualTo("test-scanner");
    }

    /** A named class, because an anonymous class has no simple name and so no default scanner name. */
    private static final class AlwaysSafeScanner implements FileScanner {
        @Override
        public ScanOutcome scan(InputStream content, String filename, long sizeBytes) {
            return ScanOutcome.safe(name());
        }
    }

    @Test
    @DisplayName("a scanner names itself by its class by default, for the audit record")
    void defaultsItsName() {
        assertThat(new AlwaysSafeScanner().name()).isEqualTo("AlwaysSafeScanner");
    }

    @Test
    @DisplayName("this module ships no FileScanner implementation, on purpose")
    void shipsNoImplementation() {
        assertThat(FileScanner.class.getPackage().getName())
                .isEqualTo("ru.ludwigandreas.fileaction.api");
        assertThat(FileScanner.class.isInterface())
                .as("the seam is an interface and the estate supplies the implementation; see the"
                        + " javadoc for why no scanner is shipped here")
                .isTrue();
    }
}
