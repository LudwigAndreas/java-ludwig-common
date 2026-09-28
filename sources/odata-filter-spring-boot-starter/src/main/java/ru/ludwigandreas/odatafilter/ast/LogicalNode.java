package ru.ludwigandreas.odatafilter.ast;

/** {@code left <and|or> right}. */
public record LogicalNode(LogicalOperator operator, FilterNode left, FilterNode right) implements FilterNode {

    @Override
    public int depth() {
        return 1 + Math.max(left.depth(), right.depth());
    }
}
