package ru.ludwigandreas.pat.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.pat.problem.PatProblemTypes;

/**
 * The problem-type constants, which exist so the edge configures against a definition rather than inventing
 * one. The assertions are mostly about distinctness and shape, because that is what a consumer branches on.
 */
class PatProblemTypesTest {

    @Test
    @DisplayName("a rejected credential and a refused credential are different types")
    void rejectedAndNotPermittedAreDistinct() {
        // The distinction a client library branches on: reissue the token, versus stop using a token here.
        // Collapsing them would send an automation user to the wrong remedy.
        assertThat(PatProblemTypes.CREDENTIAL_REJECTED)
                .isNotEqualTo(PatProblemTypes.CREDENTIAL_NOT_PERMITTED_HERE);
        assertThat(PatProblemTypes.ISSUANCE_REFUSED)
                .isNotEqualTo(PatProblemTypes.CREDENTIAL_REJECTED);
    }

    @Test
    @DisplayName("every published type is an absolute URI, as RFC 9457 requires")
    void typesAreAbsoluteUris() {
        for (String type : List.of(PatProblemTypes.CREDENTIAL_REJECTED,
                PatProblemTypes.CREDENTIAL_NOT_PERMITTED_HERE,
                PatProblemTypes.ISSUANCE_REFUSED)) {
            assertThat(URI.create(type).isAbsolute()).as(type).isTrue();
        }
    }

    @Test
    @DisplayName("the WWW-Authenticate value is the RFC 6750 invalid_token form, with no reason attached")
    void wwwAuthenticateDisclosesNothing() {
        assertThat(PatProblemTypes.WWW_AUTHENTICATE).isEqualTo("Bearer error=\"invalid_token\"");
        // The challenge must not carry error_description: anything that distinguishes revoked from expired
        // from unknown is an oracle, and this header is the easiest place to leak one by accident.
        assertThat(PatProblemTypes.WWW_AUTHENTICATE).doesNotContain("error_description");
    }

    @Test
    @DisplayName("the management path is relative, because the host is the deployment's to decide")
    void managementPathIsRelative() {
        assertThat(PatProblemTypes.MANAGEMENT_PATH).startsWith("/");
        assertThat(URI.create(PatProblemTypes.MANAGEMENT_PATH).isAbsolute()).isFalse();
    }

    @Test
    @DisplayName("every constant is public static final, since the edge configures from them")
    void constantsArePublished() {
        for (Field field : PatProblemTypes.class.getDeclaredFields()) {
            if (field.getName().startsWith("BASE")) {
                continue;
            }
            int modifiers = field.getModifiers();
            assertThat(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                    && Modifier.isFinal(modifiers))
                    .as("%s must be published for the edge to configure against", field.getName())
                    .isTrue();
        }
    }
}
