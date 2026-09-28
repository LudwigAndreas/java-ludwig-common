package ru.ludwigandreas.archrules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import ru.ludwigandreas.archrules.rules.SpringWiringRules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The documented way into an existing codebase: today's violations become the accepted baseline and
 * only new ones fail the build.
 */
class FreezeTest {

    private static final Path STORE = Path.of("target", "archunit_store");

    @BeforeEach
    void clearStore() throws IOException {
        if (Files.exists(STORE)) {
            try (var paths = Files.walk(STORE)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Test
    @DisplayName("a frozen run records the existing violations and passes")
    void existingViolationsAreFrozen() {
        ArchitectureRulesConfiguration frozen = Fixtures.configurationFor("bad.spring")
                .freeze(true)
                .build();

        // without freezing, this fixture breaks the constructor injection rule
        assertThat(Fixtures.failingRuleIds(Fixtures.configurationFor("bad.spring").build()))
                .contains(SpringWiringRules.CONSTRUCTOR_INJECTION.value());

        assertThatCode(() -> ArchitectureRules.suite(frozen).check()).doesNotThrowAnyException();
        assertThat(STORE).exists();

        // and the recorded baseline keeps it passing on the next build
        assertThatCode(() -> ArchitectureRules.suite(frozen).check()).doesNotThrowAnyException();
    }
}
