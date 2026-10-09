package ru.ludwigandreas.odatafilter.metadata;

import java.util.List;

/**
 * One property a caller may name in {@code $filter} or {@code $orderby}, as published to that caller.
 *
 * <h2>What is not here</h2>
 *
 * <p>No role names. A caller holding none of a field's roles does not see the field at all, and one that
 * does has no use for the list - so publishing it would only ever tell a caller the name of a privilege
 * it does not hold, from an endpoint whose purpose is to tell it less.
 *
 * <p>No column, no table, no Java type. {@link #type} is an API-level name - {@code string},
 * {@code integer}, {@code decimal}, {@code boolean}, {@code date}, {@code date-time}, {@code guid},
 * {@code enum} - because a client writing a literal needs to know how to spell it, not which JDBC type it
 * lands in. Publishing {@code java.math.BigDecimal} would leak the implementation and help nobody.
 *
 * @param path      the {@code /}-joined property path, exactly as a caller writes it in {@code $filter}
 * @param type      the API-level data type, for writing a literal of the right shape
 * @param operators the operators permitted on this path, lower-cased as OData spells them
 * @param sortable  whether this path may also appear in {@code $orderby}
 */
public record FilterPropertyMetadata(String path, String type, List<String> operators, boolean sortable) {

    public FilterPropertyMetadata {
        operators = operators == null ? List.of() : List.copyOf(operators);
    }
}
