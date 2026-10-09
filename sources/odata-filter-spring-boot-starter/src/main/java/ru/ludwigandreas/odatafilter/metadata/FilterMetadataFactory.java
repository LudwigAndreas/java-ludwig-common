package ru.ludwigandreas.odatafilter.metadata;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.parser.OrderByTerm;
import ru.ludwigandreas.odatafilter.policy.EntityFilterPolicy;
import ru.ludwigandreas.odatafilter.policy.FilterFieldPolicy;

/**
 * Projects a resolved {@link EntityFilterPolicy} into the document one caller may see.
 *
 * <h2>It decides nothing</h2>
 *
 * <p>Whether a caller may use a path is {@link FilterFieldPolicy#permitsRoles}, called here and not
 * re-implemented; whether a path is sortable is {@link FilterFieldPolicy#sortable}, likewise. That is the
 * whole reason the document and the query enforcement cannot drift apart: they are two readings of one
 * resolved policy rather than two descriptions of an intention. The agreement is also asserted by a test,
 * because "these two computations agree" is a property of their output and neither ArchUnit nor Checkstyle
 * can see it.
 *
 * <p>Consequence worth stating: a path omitted here is omitted because the <em>policy</em> refuses this
 * caller, so a future change that loosens the policy loosens the document in the same commit, and one that
 * tightens it tightens both.
 */
public final class FilterMetadataFactory {

    /**
     * Java type to the name a client writing a literal needs. Primitives are listed beside their boxes
     * because an entity field may be either and the policy records the declared type.
     */
    private static final Map<Class<?>, String> TYPE_NAMES = Map.ofEntries(
            Map.entry(String.class, "string"),
            Map.entry(Character.class, "string"),
            Map.entry(char.class, "string"),
            Map.entry(Boolean.class, "boolean"),
            Map.entry(boolean.class, "boolean"),
            Map.entry(UUID.class, "guid"),
            Map.entry(LocalDate.class, "date"),
            Map.entry(Instant.class, "date-time"),
            Map.entry(OffsetDateTime.class, "date-time"),
            Map.entry(ZonedDateTime.class, "date-time"),
            Map.entry(LocalDateTime.class, "date-time"),
            Map.entry(BigDecimal.class, "decimal"),
            Map.entry(Double.class, "decimal"),
            Map.entry(double.class, "decimal"),
            Map.entry(Float.class, "decimal"),
            Map.entry(float.class, "decimal"),
            Map.entry(Integer.class, "integer"),
            Map.entry(int.class, "integer"),
            Map.entry(Long.class, "integer"),
            Map.entry(long.class, "integer"),
            Map.entry(Short.class, "integer"),
            Map.entry(short.class, "integer"),
            Map.entry(Byte.class, "integer"),
            Map.entry(byte.class, "integer"),
            Map.entry(BigInteger.class, "integer"));

    private FilterMetadataFactory() {
    }

    /**
     * The document for {@code policy} as seen by a caller holding {@code callerRoles}.
     *
     * @param publishedName the name from {@code @FilterPolicy(metadataName = ...)}
     * @param policy        the resolved policy, from {@code FilterPolicyRegistry}
     * @param callerRoles   the caller's roles, from the active {@code FilterPrincipalResolver}
     * @return the document, carrying only paths this caller may actually use
     */
    public static FilterMetadata of(String publishedName, EntityFilterPolicy policy, Set<String> callerRoles) {
        List<FilterPropertyMetadata> properties = policy.fields().entrySet().stream()
                // The one line that enforces "absent, not present-and-forbidden". A caller that may not
                // use a path never learns the path exists, which is also why no role name is published:
                // there is nothing for one to annotate.
                .filter(entry -> entry.getValue().permitsRoles(callerRoles))
                .map(entry -> property(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(FilterPropertyMetadata::path))
                .toList();
        return new FilterMetadata(
                publishedName,
                properties,
                policy.maxDepth(),
                policy.maxPageSize(),
                policy.defaultPageSize(),
                policy.maxNestedPropertyDepth(),
                orderByText(policy.defaultOrderBy()));
    }

    private static FilterPropertyMetadata property(String path, FilterFieldPolicy field) {
        List<String> operators = field.allowedOperators().stream()
                .sorted(Comparator.comparing(FilterOperator::name))
                .map(operator -> operator.name().toLowerCase(Locale.ROOT))
                .toList();
        return new FilterPropertyMetadata(path, typeName(field.javaType()), operators, field.sortable());
    }

    /** Rendered in {@code $orderby} syntax, so a client can send it back verbatim if it wants to. */
    private static String orderByText(List<OrderByTerm> terms) {
        return terms.stream()
                .map(term -> term.descending() ? term.propertyPath() + " desc" : term.propertyPath() + " asc")
                .collect(Collectors.joining(", "));
    }

    /**
     * The API-level name of a type, not its Java name.
     *
     * <p>A client needs to know how to spell a literal - quoted, bare, ISO-8601 - and that is what these
     * names say. Publishing {@code java.math.BigDecimal} or {@code java.time.OffsetDateTime} would leak the
     * implementation and tell a client nothing it can act on.
     *
     * <p>A table rather than a chain of comparisons, because the chain was a 28-branch method that
     * Checkstyle refused and a reader would have had to verify line by line. An unrecognised type is
     * reported as {@code string}, which is how the parser will treat its literal anyway.
     */
    private static String typeName(Class<?> javaType) {
        if (javaType == null) {
            return "string";
        }
        if (javaType.isEnum()) {
            return "enum";
        }
        return TYPE_NAMES.getOrDefault(javaType, "string");
    }
}
