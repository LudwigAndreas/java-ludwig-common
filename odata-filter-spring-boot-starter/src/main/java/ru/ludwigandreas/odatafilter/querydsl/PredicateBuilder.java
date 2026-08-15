package ru.ludwigandreas.odatafilter.querydsl;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Ops;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.PathBuilder;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import ru.ludwigandreas.odatafilter.ast.ComparisonNode;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.ast.FunctionNode;
import ru.ludwigandreas.odatafilter.ast.InNode;
import ru.ludwigandreas.odatafilter.ast.Literal;
import ru.ludwigandreas.odatafilter.ast.LiteralKind;
import ru.ludwigandreas.odatafilter.ast.LogicalNode;
import ru.ludwigandreas.odatafilter.ast.LogicalOperator;
import ru.ludwigandreas.odatafilter.ast.NotNode;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;
import ru.ludwigandreas.odatafilter.exception.UnfilterableFieldException;
import ru.ludwigandreas.odatafilter.policy.EntityFilterPolicy;
import ru.ludwigandreas.odatafilter.policy.FilterFieldPolicy;

/**
 * Translates a validated {@link FilterNode} AST into a QueryDSL {@link Predicate}, using
 * {@link PathBuilder} to reach entity properties dynamically instead of requiring
 * annotation-processor-generated {@code QEntity} classes on the consumer's classpath. Every leaf
 * is built with {@link Expressions#predicate(com.querydsl.core.types.Operator, Expression[])},
 * which is agnostic to the concrete {@code Path} subtype - so a single code path handles strings,
 * numbers, booleans, dates, enums and associations uniformly instead of juggling QueryDSL's
 * per-type expression classes.
 *
 * <p>Callers must run {@link ru.ludwigandreas.odatafilter.validation.FieldAccessValidator} first;
 * this class re-checks field existence as a defense-in-depth measure but does not re-check
 * operator/role permissions.
 */
public final class PredicateBuilder {

    private static final int MAX_IN_VALUES = 500;

    public Predicate build(EntityFilterPolicy policy, FilterNode node) {
        return build(rootPath(policy.entityType()), policy, node);
    }

    private BooleanExpression build(PathBuilder<?> root, EntityFilterPolicy policy, FilterNode node) {
        if (node instanceof LogicalNode logical) {
            BooleanExpression left = build(root, policy, logical.left());
            BooleanExpression right = build(root, policy, logical.right());
            return logical.operator() == LogicalOperator.AND ? left.and(right) : left.or(right);
        }
        if (node instanceof NotNode not) {
            return build(root, policy, not.operand()).not();
        }
        if (node instanceof ComparisonNode comparison) {
            return comparison(root, policy, comparison);
        }
        if (node instanceof FunctionNode function) {
            return function(root, policy, function);
        }
        if (node instanceof InNode in) {
            return inList(root, policy, in);
        }
        throw new IllegalStateException("Unhandled filter node type: " + node.getClass());
    }

    private BooleanExpression comparison(PathBuilder<?> root, EntityFilterPolicy policy, ComparisonNode node) {
        FilterFieldPolicy field = requireField(policy, node.propertyPath());
        Expression<?> path = typedPath(root, node.propertyPath(), field.javaType());
        if (node.value().kind() == LiteralKind.NULL) {
            return switch (node.operator()) {
                case EQ -> Expressions.predicate(Ops.IS_NULL, path);
                case NE -> Expressions.predicate(Ops.IS_NOT_NULL, path);
                default -> throw new FilterSyntaxException(
                        "Operator '" + node.operator() + "' cannot be combined with null on '" + node.propertyPath() + "'");
            };
        }
        Object value = coerce(node.value(), field.javaType(), node.propertyPath());
        Ops op = switch (node.operator()) {
            case EQ -> Ops.EQ;
            case NE -> Ops.NE;
            case GT -> Ops.GT;
            case GE -> Ops.GOE;
            case LT -> Ops.LT;
            case LE -> Ops.LOE;
        };
        return Expressions.predicate(op, path, Expressions.constant(value));
    }

    private BooleanExpression function(PathBuilder<?> root, EntityFilterPolicy policy, FunctionNode node) {
        FilterFieldPolicy field = requireField(policy, node.propertyPath());
        if (field.javaType() != String.class) {
            throw new UnfilterableFieldException(
                    node.propertyPath(), node.function() + "() only applies to string properties");
        }
        Expression<?> path = typedPath(root, node.propertyPath(), String.class);
        Ops op = switch (node.function()) {
            case CONTAINS -> Ops.STRING_CONTAINS;
            case STARTSWITH -> Ops.STARTS_WITH;
            case ENDSWITH -> Ops.ENDS_WITH;
        };
        return Expressions.predicate(op, path, Expressions.constant(node.argument().rawText()));
    }

    private BooleanExpression inList(PathBuilder<?> root, EntityFilterPolicy policy, InNode node) {
        FilterFieldPolicy field = requireField(policy, node.propertyPath());
        List<Literal> values = node.values();
        if (values.size() > MAX_IN_VALUES) {
            throw new FilterSyntaxException(
                    "'in' list for '" + node.propertyPath() + "' exceeds the maximum of " + MAX_IN_VALUES + " values");
        }
        if (values.isEmpty()) {
            return Expressions.FALSE;
        }
        Expression<?> path = typedPath(root, node.propertyPath(), field.javaType());
        BooleanExpression result = null;
        for (Literal literal : values) {
            Object value = coerce(literal, field.javaType(), node.propertyPath());
            BooleanExpression eq = Expressions.predicate(Ops.EQ, path, Expressions.constant(value));
            result = result == null ? eq : result.or(eq);
        }
        return result;
    }

    private Expression<?> typedPath(PathBuilder<?> root, String propertyPath, Class<?> javaType) {
        String[] segments = propertyPath.split("/");
        PathBuilder<?> parent = root;
        for (int i = 0; i < segments.length - 1; i++) {
            parent = parent.get(segments[i]);
        }
        return parent.get(segments[segments.length - 1], box(javaType));
    }

    private FilterFieldPolicy requireField(EntityFilterPolicy policy, String path) {
        return policy.field(path)
                .orElseThrow(() -> new UnfilterableFieldException(path, "unknown or not filterable"));
    }

    private static PathBuilder<?> rootPath(Class<?> entityType) {
        String simpleName = entityType.getSimpleName();
        String alias = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        return new PathBuilder<>(entityType, alias);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object coerce(Literal literal, Class<?> targetType, String propertyPath) {
        if (literal.kind() == LiteralKind.NULL) {
            return null;
        }
        Class<?> boxed = box(targetType);
        try {
            if (boxed.isEnum()) {
                return Enum.valueOf((Class) boxed, literal.rawText());
            }
            if (boxed == UUID.class) {
                return UUID.fromString(literal.rawText());
            }
            return switch (literal.kind()) {
                case STRING -> coerceString(literal.rawText(), boxed);
                case INTEGER, DECIMAL, DOUBLE -> coerceNumeric(literal.rawText(), boxed);
                case BOOLEAN -> Boolean.valueOf(literal.rawText());
                case DATE -> coerceDate(literal.rawText(), boxed);
                case DATE_TIME_OFFSET -> coerceDateTime(literal.rawText(), boxed);
                case GUID -> UUID.fromString(literal.rawText());
                case NULL -> null;
            };
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new FilterSyntaxException(
                    "Cannot convert '" + literal.rawText() + "' to " + targetType.getSimpleName()
                            + " for property '" + propertyPath + "'",
                    e);
        }
    }

    private static Object coerceString(String raw, Class<?> boxed) {
        if (boxed == String.class) {
            return raw;
        }
        throw new IllegalArgumentException("string literal is not compatible with target type " + boxed);
    }

    private static Object coerceNumeric(String raw, Class<?> boxed) {
        if (boxed == Integer.class) {
            return Integer.valueOf(raw);
        }
        if (boxed == Long.class) {
            return Long.valueOf(raw);
        }
        if (boxed == Short.class) {
            return Short.valueOf(raw);
        }
        if (boxed == Byte.class) {
            return Byte.valueOf(raw);
        }
        if (boxed == Double.class) {
            return Double.valueOf(raw);
        }
        if (boxed == Float.class) {
            return Float.valueOf(raw);
        }
        if (boxed == BigDecimal.class) {
            return new BigDecimal(raw);
        }
        if (boxed == BigInteger.class) {
            return new BigInteger(raw);
        }
        throw new IllegalArgumentException("unsupported numeric target type " + boxed);
    }

    private static Object coerceDate(String raw, Class<?> boxed) {
        LocalDate date = LocalDate.parse(raw);
        if (boxed == LocalDate.class) {
            return date;
        }
        if (boxed == java.sql.Date.class || boxed == java.util.Date.class) {
            return java.sql.Date.valueOf(date);
        }
        throw new IllegalArgumentException("unsupported date target type " + boxed);
    }

    private static Object coerceDateTime(String raw, Class<?> boxed) {
        OffsetDateTime offsetDateTime = OffsetDateTime.parse(raw);
        if (boxed == OffsetDateTime.class) {
            return offsetDateTime;
        }
        if (boxed == Instant.class) {
            return offsetDateTime.toInstant();
        }
        if (boxed == LocalDateTime.class) {
            return offsetDateTime.toLocalDateTime();
        }
        if (boxed == ZonedDateTime.class) {
            return offsetDateTime.toZonedDateTime();
        }
        if (boxed == java.util.Date.class) {
            return java.util.Date.from(offsetDateTime.toInstant());
        }
        if (boxed == java.sql.Timestamp.class) {
            return java.sql.Timestamp.from(offsetDateTime.toInstant());
        }
        throw new IllegalArgumentException("unsupported date-time target type " + boxed);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }
}
