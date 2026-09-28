package ru.ludwigandreas.odatafilter.ast;

/**
 * Parsed representation of an OData {@code $filter} expression (or a sub-expression thereof).
 * Produced by {@code ODataFilterParser} and consumed by the QueryDSL predicate builder and
 * the built-in validators. Deliberately independent of any persistence API so that it can be
 * unit-tested and validated without a JPA metamodel.
 */
public sealed interface FilterNode
        permits ComparisonNode, LogicalNode, NotNode, FunctionNode, InNode {

    /**
     * Nesting depth of this (sub-)expression, counting boolean composition only
     * ({@code and}/{@code or}/{@code not}/grouping) - the dimension enterprises actually
     * need to bound to protect the SQL/JPQL translator and the database planner. A bare
     * comparison, function call or {@code in} predicate has depth 1.
     */
    int depth();
}
