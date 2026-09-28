package ru.ludwigandreas.odatafilter.annotation;

/**
 * Operators that can be granted to a field via {@link Filterable#ops()}.
 */
public enum FilterOperator {
    EQ, NE, GT, GE, LT, LE, CONTAINS, STARTSWITH, ENDSWITH, IN;

    public static final FilterOperator[] COMPARISON_ONLY = {EQ, NE, GT, GE, LT, LE};
    public static final FilterOperator[] ALL = values();
}
