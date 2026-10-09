package ru.ludwigandreas.odatafilter.ast;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What a filter <em>asked about</em>, with none of what it asked for: the property paths the caller
 * named and the operators applied to each, and no literal value in any form.
 *
 * <h2>Why the values are not here, in any shape</h2>
 *
 * <p>This exists because the audit trail is retained for years and read by people who are not
 * entitled to the data it guards, which is {@code audit-envelope}'s rule that an event records which
 * object was touched and never its contents. A filter's literals are the contents: {@code email eq
 * 'a.sidorov@example.com'} is a personal e-mail address, {@code documentNumber eq '4012...'} is a
 * document number, and both would otherwise have been written verbatim into the trail.
 *
 * <p>They are not hashed and not truncated either. A hash is reversible for any value drawn from a
 * small set, which a status, a channel, a country code and most enumerations are; a truncation leaks
 * exactly the identifying prefix. The only safe treatment of a value nobody needs is not to collect
 * it, so {@link #of} never reads {@code Literal}. That is also why this needs no
 * {@code Redaction.MASK}: there is nothing here to mask.
 *
 * <p>What survives is enough for the question the trail exists to answer - which caller queried which
 * properties of which resource, and how. {@code price gt, email eq} says a caller filtered on an
 * e-mail address without saying which one.
 *
 * <p>It lives beside the AST it is derived from rather than in the {@code audit} package it feeds,
 * because it describes a <em>filter</em> and not an audit concept - and because
 * {@code audit.audit-types-produce-audit-events} is right to insist that a type in a module's audit
 * package produces an {@link ru.ludwigandreas.odatafilter.audit.FilterAppliedEvent}'s envelope. This one
 * produces a value; the event produces the envelope.
 *
 * @param paths     the {@code /}-joined property paths named anywhere in the expression, sorted
 * @param operators one {@code path operator} pair per distinct combination, sorted
 *
 *                  <p>Both are sorted rather than kept in the order encountered, and that is
 *                  deliberate: the walk order is the shape of the parse tree, not the order the caller
 *                  wrote the terms in, so "first seen" would have been a guarantee that looks like
 *                  source order and is not. Sorted, two filters that ask the same question summarise to
 *                  the same text whatever order their terms were written in - which is what lets a SIEM
 *                  rule match on it and an auditor group by it.
 */
public record FilterSummary(Set<String> paths, Set<String> operators) {

    public FilterSummary {
        // Unmodifiable but iteration-order-preserving: Set.copyOf would discard the sort that makes two
        // equivalent filters compare equal as text.
        paths = Collections.unmodifiableSet(new TreeSet<>(paths));
        operators = Collections.unmodifiableSet(new TreeSet<>(operators));
    }

    /** An empty summary, for a caller that supplied no filter at all. */
    public static FilterSummary empty() {
        return new FilterSummary(Set.of(), Set.of());
    }

    /**
     * Walks a validated AST and collects the paths and operators.
     *
     * <p>Takes the AST rather than the raw {@code $filter} string on purpose: the string is the
     * literals, and a summary derived by stripping them out of text would be a parser of its own that
     * has to be right every time or it leaks. Walking the tree cannot reach a value unless it asks for
     * one, and nothing here asks.
     */
    public static FilterSummary of(FilterNode root) {
        if (root == null) {
            return empty();
        }
        Map<String, Set<String>> byPath = new LinkedHashMap<>();
        collect(root, byPath);
        Set<String> operators = byPath.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(operator -> entry.getKey() + " " + operator))
                .collect(Collectors.toCollection(TreeSet::new));
        return new FilterSummary(new TreeSet<>(byPath.keySet()), operators);
    }

    private static void collect(FilterNode node, Map<String, Set<String>> byPath) {
        if (node instanceof LogicalNode logical) {
            collect(logical.left(), byPath);
            collect(logical.right(), byPath);
        } else if (node instanceof NotNode not) {
            collect(not.operand(), byPath);
        } else if (node instanceof ComparisonNode comparison) {
            // comparison.value() is deliberately not read.
            add(byPath, comparison.propertyPath(), comparison.operator().name().toLowerCase(Locale.ROOT));
        } else if (node instanceof FunctionNode function) {
            // function.argument() is deliberately not read.
            add(byPath, function.propertyPath(), function.function().name().toLowerCase(Locale.ROOT));
        } else if (node instanceof InNode in) {
            // in.values() is deliberately not read - not even its size, which for a single-element
            // list would say the caller named one specific thing.
            add(byPath, in.propertyPath(), "in");
        } else {
            throw new IllegalStateException("Unhandled filter node type: " + node.getClass());
        }
    }

    private static void add(Map<String, Set<String>> byPath, String path, String operator) {
        byPath.computeIfAbsent(path, key -> new LinkedHashSet<>()).add(operator);
    }

    /** The operators as one stable string, which is what reaches an audit attribute. */
    public String operatorsAsText() {
        return String.join(", ", operators);
    }

    /** The paths as one stable string. */
    public String pathsAsText() {
        return String.join(", ", paths);
    }

    /** Whether the caller filtered on anything at all. */
    public boolean isEmpty() {
        return paths.isEmpty();
    }
}
