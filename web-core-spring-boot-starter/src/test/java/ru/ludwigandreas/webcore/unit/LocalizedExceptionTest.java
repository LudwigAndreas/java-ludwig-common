package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The contract a service's own exceptions inherit: it carries a code and arguments, never a
 * formatted user-facing string, and its {@code getMessage()} stays developer-facing.
 */
class LocalizedExceptionTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    static class ThingNotFoundException extends LocalizedException {
        ThingNotFoundException(UUID id) {
            super(ProblemStatus.NOT_FOUND, "error.thing.not-found", id);
        }
    }

    static class ThingBrokenException extends LocalizedException {
        ThingBrokenException(Throwable cause) {
            super(ProblemStatus.INTERNAL, "error.thing.broken", cause);
        }
    }

    @Test
    @DisplayName("carries the outcome, the code and the arguments through to a definition")
    void convertsToDefinition() {
        ProblemDefinition definition = new ThingNotFoundException(ID).toDefinition();

        assertThat(definition.status()).isEqualTo(ProblemStatus.NOT_FOUND);
        assertThat(definition.code()).isEqualTo("error.thing.not-found");
        assertThat(definition.args()).containsExactly(ID);
    }

    @Test
    @DisplayName("getMessage() is for the log: the code and its arguments, not a localized sentence")
    void messageIsDeveloperFacing() {
        assertThat(new ThingNotFoundException(ID).getMessage())
                .isEqualTo("error.thing.not-found [00000000-0000-0000-0000-0000000000ff]");
    }

    @Test
    @DisplayName("a cause is retained for the log without being rendered into the definition")
    void retainsCause() {
        Throwable cause = new IllegalStateException("driver said no");

        ThingBrokenException exception = new ThingBrokenException(cause);

        assertThat(exception).hasCause(cause);
        assertThat(exception.toDefinition().args()).isEmpty();
    }

    @Test
    @DisplayName("withProperty is chainable at the throw site and keeps the subclass's type")
    void withPropertyIsChainableAndTyped() {
        ThingNotFoundException exception =
                new ThingNotFoundException(ID).<ThingNotFoundException>withProperty("thingId", ID);

        assertThat(exception.getProperties()).containsEntry("thingId", ID);
        assertThat(exception.toDefinition().properties()).containsEntry("thingId", ID);
    }

    @Test
    @DisplayName("the exposed argument array and property map cannot be mutated from outside")
    void exposesDefensiveCopies() {
        ThingNotFoundException exception = new ThingNotFoundException(ID);

        exception.getArgs()[0] = "tampered";

        assertThat(exception.getArgs()).containsExactly(ID);
        assertThatThrownBy(() -> exception.getProperties().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("it still renders after a Java serialization round trip, one sentence poorer")
    void survivesSerialization() throws Exception {
        // Message arguments are arbitrary domain objects with no serialization contract, so they are
        // transient. What has to survive is the outcome and the code - and rendering must not throw.
        ThingNotFoundException original = new ThingNotFoundException(ID);

        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        LocalizedException restored;
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (LocalizedException) in.readObject();
        }

        assertThat(restored.getStatus()).isEqualTo(ProblemStatus.NOT_FOUND);
        assertThat(restored.getCode()).isEqualTo("error.thing.not-found");
        assertThat(restored.getArgs()).isEmpty();
        assertThat(restored.getProperties()).isEmpty();
        assertThat(restored.toDefinition().code()).isEqualTo("error.thing.not-found");
    }

    @Test
    @DisplayName("an exception with no arguments still produces a usable definition")
    void handlesNoArguments() {
        LocalizedException exception = new LocalizedException(ProblemStatus.FORBIDDEN, "error.nope") {
        };

        assertThat(exception.toDefinition().args()).isEmpty();
        assertThat(exception.toDefinition().status()).isEqualTo(ProblemStatus.FORBIDDEN);
    }
}
