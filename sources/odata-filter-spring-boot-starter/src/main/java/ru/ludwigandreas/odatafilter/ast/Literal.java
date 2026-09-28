package ru.ludwigandreas.odatafilter.ast;

/**
 * A literal value as it appeared in the raw {@code $filter} string, still unparsed with
 * respect to the target property's Java type. {@code rawText} never includes the surrounding
 * quotes for {@link LiteralKind#STRING}.
 */
public record Literal(LiteralKind kind, String rawText) {

    public static Literal ofNull() {
        return new Literal(LiteralKind.NULL, null);
    }
}
