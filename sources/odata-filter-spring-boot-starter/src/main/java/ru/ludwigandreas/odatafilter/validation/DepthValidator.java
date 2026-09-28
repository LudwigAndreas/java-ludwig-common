package ru.ludwigandreas.odatafilter.validation;

import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.exception.FilterDepthExceededException;

/** Always-on guard against pathologically nested {@code $filter} expressions. */
public final class DepthValidator {

    private DepthValidator() {
    }

    public static void validate(FilterNode root, int maxDepth) {
        int actual = root.depth();
        if (actual > maxDepth) {
            throw new FilterDepthExceededException(actual, maxDepth);
        }
    }
}
