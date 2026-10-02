package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.fileaction.api.DocumentHandler;
import ru.ludwigandreas.fileaction.api.FileActionHandler;
import ru.ludwigandreas.fileaction.api.RowHandler;

/**
 * That {@link FileActionHandler} permits exactly two shapes.
 *
 * <p>A third shape is a compile error, which no test can assert from inside the same compilation unit -
 * so what this asserts instead is the permitted set, which is what a change widening it would have to
 * edit. The reason the set is two and not three is in the interface's javadoc.
 */
class HandlerSealingTest {

    @Test
    @DisplayName("the handler hierarchy is sealed")
    void isSealed() {
        assertThat(FileActionHandler.class.isSealed()).isTrue();
    }

    @Test
    @DisplayName("exactly RowHandler and DocumentHandler are permitted")
    void permitsExactlyTwoShapes() {
        assertThat(Arrays.asList(FileActionHandler.class.getPermittedSubclasses()))
                .containsExactlyInAnyOrder(RowHandler.class, DocumentHandler.class);
    }

    @Test
    @DisplayName("both shapes carry the binding, so a handler and its binding cannot be mispaired")
    void bothShapesDeclareTheBinding() {
        assertThat(RowHandler.class).matches(FileActionHandler.class::isAssignableFrom);
        assertThat(DocumentHandler.class).matches(FileActionHandler.class::isAssignableFrom);
    }
}
