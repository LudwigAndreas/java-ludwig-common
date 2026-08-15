package ru.ludwigandreas.odatafilter.ast;

/** {@code contains(propertyPath, 'value')}, {@code startswith(...)}, {@code endswith(...)}. */
public record FunctionNode(StringFunction function, String propertyPath, Literal argument) implements FilterNode {

    @Override
    public int depth() {
        return 1;
    }
}
