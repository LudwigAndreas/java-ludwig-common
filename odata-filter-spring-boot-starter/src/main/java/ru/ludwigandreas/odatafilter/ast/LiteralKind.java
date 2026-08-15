package ru.ludwigandreas.odatafilter.ast;

/**
 * Coarse-grained kind of an OData literal token, as produced by the tokenizer.
 * Final coercion to the target property's Java type happens in the predicate builder.
 */
public enum LiteralKind {
    STRING,
    INTEGER,
    DECIMAL,
    DOUBLE,
    BOOLEAN,
    DATE,
    DATE_TIME_OFFSET,
    GUID,
    NULL
}
