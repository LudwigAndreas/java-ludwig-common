package ru.ludwigandreas.odatafilter.validation;

import java.util.List;
import java.util.Set;
import ru.ludwigandreas.odatafilter.annotation.FilterOperator;
import ru.ludwigandreas.odatafilter.ast.ComparisonNode;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.ast.FunctionNode;
import ru.ludwigandreas.odatafilter.ast.InNode;
import ru.ludwigandreas.odatafilter.ast.LogicalNode;
import ru.ludwigandreas.odatafilter.ast.NotNode;
import ru.ludwigandreas.odatafilter.exception.FilterAccessDeniedException;
import ru.ludwigandreas.odatafilter.exception.UnfilterableFieldException;
import ru.ludwigandreas.odatafilter.parser.OrderByTerm;
import ru.ludwigandreas.odatafilter.policy.EntityFilterPolicy;
import ru.ludwigandreas.odatafilter.policy.FilterFieldPolicy;

/**
 * Always-on guard enforcing the entity's field allow-list: every property path referenced in the
 * filter (or {@code $orderby}) must be {@code @Filterable}, used with an operator it grants, and
 * reachable by the caller's roles.
 */
public final class FieldAccessValidator {

    private FieldAccessValidator() {
    }

    public static void validate(EntityFilterPolicy policy, FilterNode node, Set<String> callerRoles) {
        if (node instanceof LogicalNode logical) {
            validate(policy, logical.left(), callerRoles);
            validate(policy, logical.right(), callerRoles);
        } else if (node instanceof NotNode not) {
            validate(policy, not.operand(), callerRoles);
        } else if (node instanceof ComparisonNode comparison) {
            checkField(policy, comparison.propertyPath(), toOperator(comparison), callerRoles);
        } else if (node instanceof FunctionNode function) {
            checkField(policy, function.propertyPath(), toOperator(function), callerRoles);
        } else if (node instanceof InNode in) {
            checkField(policy, in.propertyPath(), FilterOperator.IN, callerRoles);
        }
    }

    public static void validateOrderBy(EntityFilterPolicy policy, List<OrderByTerm> terms, Set<String> callerRoles) {
        for (OrderByTerm term : terms) {
            FilterFieldPolicy field = policy.field(term.propertyPath())
                    .orElseThrow(() -> new UnfilterableFieldException(term.propertyPath(), "unknown or not filterable"));
            if (!field.sortable()) {
                throw new UnfilterableFieldException(term.propertyPath(), "not sortable");
            }
            if (!field.permitsRoles(callerRoles)) {
                throw new FilterAccessDeniedException(term.propertyPath());
            }
        }
    }

    private static void checkField(EntityFilterPolicy policy, String path, FilterOperator operator, Set<String> callerRoles) {
        FilterFieldPolicy field = policy.field(path)
                .orElseThrow(() -> new UnfilterableFieldException(path, "unknown or not filterable"));
        if (!field.permitsOperator(operator)) {
            throw new UnfilterableFieldException(path, "operator '" + operator + "' is not permitted");
        }
        if (!field.permitsRoles(callerRoles)) {
            throw new FilterAccessDeniedException(path);
        }
    }

    private static FilterOperator toOperator(ComparisonNode node) {
        return switch (node.operator()) {
            case EQ -> FilterOperator.EQ;
            case NE -> FilterOperator.NE;
            case GT -> FilterOperator.GT;
            case GE -> FilterOperator.GE;
            case LT -> FilterOperator.LT;
            case LE -> FilterOperator.LE;
        };
    }

    private static FilterOperator toOperator(FunctionNode node) {
        return switch (node.function()) {
            case CONTAINS -> FilterOperator.CONTAINS;
            case STARTSWITH -> FilterOperator.STARTSWITH;
            case ENDSWITH -> FilterOperator.ENDSWITH;
        };
    }
}
