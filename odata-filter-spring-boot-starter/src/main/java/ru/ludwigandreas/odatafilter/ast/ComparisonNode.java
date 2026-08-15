package ru.ludwigandreas.odatafilter.ast;

/** {@code propertyPath <op> value}, e.g. {@code Age gt 18} or {@code Department/Name eq 'Sales'}. */
public record ComparisonNode(String propertyPath, ComparisonOperator operator, Literal value) implements FilterNode {

    @Override
    public int depth() {
        return 1;
    }
}
