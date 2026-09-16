package ru.ludwigandreas.archrules;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;

/**
 * Stand-in rule for the tests that are about toggling and configuration rather than about any
 * ArchUnit behaviour - it is never evaluated, only carried around inside an {@link ArchitectureRule}.
 */
final class NoOpArchRule implements ArchRule {

    @Override
    public void check(JavaClasses classes) {
        // nothing to check
    }

    @Override
    public ArchRule because(String reason) {
        return this;
    }

    @Override
    public ArchRule allowEmptyShould(boolean allowEmptyShould) {
        return this;
    }

    @Override
    public ArchRule as(String newDescription) {
        return this;
    }

    @Override
    public EvaluationResult evaluate(JavaClasses classes) {
        throw new UnsupportedOperationException("The no-op rule is never evaluated");
    }

    @Override
    public String getDescription() {
        return "no-op";
    }
}
