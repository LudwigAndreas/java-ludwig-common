package ru.ludwigandreas.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.archrules.rules.IdentityRules;

class IdentityRulesTest {

    @Test
    @DisplayName("a save or delete through a projection repository outside the projection is reported; a read is not")
    void directWritesAreReported() {
        List<String> violations = Fixtures.violationDescriptions(Fixtures.configurationFor("bad.identity").build(),
                IdentityRules.WRITES_GO_THROUGH_THE_PROJECTION);

        assertThat(violations)
                .anyMatch(description -> description.contains("RecipientSeeder.seed") && description.contains("save"))
                .anyMatch(description -> description.contains("RecipientSeeder.wipe")
                        && description.contains("deleteAll"))
                .noneMatch(description -> description.contains("RecipientReader"));
    }
}
