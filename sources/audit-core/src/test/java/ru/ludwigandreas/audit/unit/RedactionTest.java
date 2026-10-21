package ru.ludwigandreas.audit.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.audit.redaction.Redaction;

class RedactionTest {

    @Test
    @DisplayName("only the platform's marker is a mask; the two it replaced are ordinary values")
    void recognisesOnlyTheOneMarker() {
        assertThat(Redaction.isMask(Redaction.MASK)).isTrue();
        assertThat(Redaction.isMask("[redacted]")).isFalse();
        assertThat(Redaction.isMask("****")).isFalse();
        assertThat(Redaction.isMask(null)).isFalse();
    }
}
