package ru.ludwigandreas.odatafilter.ast;

/** {@code not operand}. */
public record NotNode(FilterNode operand) implements FilterNode {

    @Override
    public int depth() {
        return 1 + operand.depth();
    }
}
