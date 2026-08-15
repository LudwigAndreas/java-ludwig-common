package ru.ludwigandreas.odatafilter.ast;

import java.util.List;

/** {@code propertyPath in (1, 2, 3)}. */
public record InNode(String propertyPath, List<Literal> values) implements FilterNode {

    @Override
    public int depth() {
        return 1;
    }
}
