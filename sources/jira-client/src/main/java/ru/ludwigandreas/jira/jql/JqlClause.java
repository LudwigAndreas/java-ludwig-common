package ru.ludwigandreas.jira.jql;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A boolean condition in a JQL {@code WHERE} clause, composable with {@code AND}, {@code OR} and
 * {@code NOT}.
 *
 * <p>Composition parenthesizes eagerly. {@code a.and(b).or(c)} renders {@code (a AND b) OR c} rather than
 * relying on JQL's precedence rules being what the author assumed - a nested clause is always wrapped, so
 * the rendered query means what the Java expression reads as. The redundant parentheses cost nothing:
 * Jira parses the query, it does not display it.
 */
public interface JqlClause {

    /** A clause written by hand, inserted verbatim - the escape hatch for syntax this builder cannot express. */
    static JqlClause raw(String jql) {
        return new RawClause(jql);
    }

    /** Negates a clause. */
    static JqlClause not(JqlClause clause) {
        return new NotClause(clause);
    }

    /** Conjunction of several clauses; an empty or single-element list is handled without stray parentheses. */
    static JqlClause allOf(List<JqlClause> clauses) {
        return CompositeClause.of("AND", clauses);
    }

    /** Disjunction of several clauses. */
    static JqlClause anyOf(List<JqlClause> clauses) {
        return CompositeClause.of("OR", clauses);
    }

    /** The rendered JQL for this clause, at top level. */
    String render();

    /**
     * The rendered JQL for this clause when it appears inside another one.
     *
     * <p>Composite clauses override this to wrap themselves in parentheses; leaf clauses do not need to.
     *
     * @return the rendered fragment, parenthesized if this clause is composite
     */
    default String renderNested() {
        return render();
    }

    /** This clause and another. */
    default JqlClause and(JqlClause other) {
        return CompositeClause.of("AND", List.of(this, other));
    }

    /** This clause or another. */
    default JqlClause or(JqlClause other) {
        return CompositeClause.of("OR", List.of(this, other));
    }

    /** A leaf clause: a rendered fragment with no internal structure. */
    record RawClause(String jql) implements JqlClause {

        @Override
        public String render() {
            return jql;
        }
    }

    /** A negated clause. */
    record NotClause(JqlClause inner) implements JqlClause {

        @Override
        public String render() {
            return "NOT " + inner.renderNested();
        }

        @Override
        public String renderNested() {
            return "(" + render() + ")";
        }
    }

    /** A conjunction or disjunction of two or more clauses. */
    record CompositeClause(String operator, List<JqlClause> clauses) implements JqlClause {

        /** Normalizes {@code clauses} to an immutable list. */
        public CompositeClause {
            clauses = List.copyOf(clauses);
        }

        /**
         * Builds a composite, collapsing the degenerate cases: an empty list has no rendering, so it is
         * rejected, and a single clause is returned as itself rather than wrapped in a one-element
         * composite that would render stray parentheses.
         *
         * @param operator {@code AND} or {@code OR}
         * @param clauses the operands
         * @return the combined clause
         */
        static JqlClause of(String operator, List<JqlClause> clauses) {
            List<JqlClause> present = clauses.stream().filter(java.util.Objects::nonNull).toList();
            if (present.isEmpty()) {
                throw new IllegalArgumentException("A " + operator + " needs at least one clause");
            }
            return present.size() == 1 ? present.get(0) : new CompositeClause(operator, present);
        }

        @Override
        public String render() {
            return clauses.stream().map(JqlClause::renderNested).collect(Collectors.joining(" " + operator + " "));
        }

        @Override
        public String renderNested() {
            return "(" + render() + ")";
        }
    }
}
