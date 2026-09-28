package ru.ludwigandreas.jira.jql;

import java.util.Locale;
import java.util.Set;

/**
 * Base for the typed JQL field references.
 *
 * <p>Two things happen here that a caller would otherwise have to get right by hand.
 *
 * <p><b>Field names are quoted when they need to be.</b> A custom field called {@code Story Points} has to
 * be written {@code "Story Points"} in JQL, and a field whose name collides with a JQL reserved word has to
 * be quoted even when it is a single word. {@link #renderName(String)} applies both rules, and leaves
 * already-safe names and the {@code cf[10004]} form untouched.
 *
 * <p><b>Operators are restricted by subtype.</b> A text field gets {@code ~}, a date field gets
 * {@code &gt;=}, and neither gets the other's - because Jira rejects {@code summary &gt;= "x"} at query time
 * and this library would rather that be a compile error. That is the substance of what "type-safe JQL"
 * means here: the subclass you hold decides which methods exist.
 */
public abstract class JqlField {

    /**
     * JQL's reserved words. A field whose name is one of these must be quoted even though it looks like a
     * plain identifier. Taken from the Jira Server 9 JQL reference; kept complete rather than trimmed to
     * "the ones anyone would actually name a field", because the failure mode is a parse error in
     * production on the one instance that did.
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "abort", "access", "add", "after", "alias", "all", "alter", "and", "any", "as", "asc",
            "audit", "avg", "before", "begin", "between", "boolean", "break", "by", "byte", "catch",
            "cf", "char", "character", "check", "checkpoint", "collate", "collation", "column", "commit",
            "connect", "continue", "count", "create", "current", "date", "decimal", "declare", "decrement",
            "default", "defaults", "define", "delete", "delimiter", "desc", "difference", "distinct",
            "divide", "do", "double", "drop", "else", "empty", "encoding", "end", "equals", "escape",
            "exclusive", "exec", "execute", "exists", "explain", "false", "fetch", "file", "field",
            "first", "float", "for", "from", "function", "go", "goto", "grant", "greater", "group",
            "having", "identified", "if", "immediate", "in", "increment", "index", "initial", "inner",
            "inout", "input", "insert", "int", "integer", "intersect", "intersection", "into", "is",
            "isempty", "isnull", "join", "last", "left", "less", "like", "limit", "lock", "long", "max",
            "min", "minus", "mode", "modify", "modulo", "more", "multiply", "next", "noaudit", "not",
            "notin", "nowait", "null", "number", "object", "of", "on", "option", "or", "order", "outer",
            "output", "power", "previous", "prior", "privileges", "public", "raise", "raw", "remainder",
            "rename", "resource", "return", "returns", "revoke", "right", "row", "rowid", "rownum",
            "rows", "select", "session", "set", "share", "size", "sqrt", "start", "strict", "string",
            "subtract", "sum", "synonym", "table", "then", "to", "trans", "transaction", "trigger",
            "true", "uid", "union", "unique", "update", "user", "validate", "values", "view", "when",
            "whenever", "where", "while", "with");

    private final String name;

    /**
     * Creates a reference to a field.
     *
     * @param name the field's JQL name, quoted here if it needs to be
     */
    protected JqlField(String name) {
        this.name = renderName(name);
    }

    /**
     * Quotes a field name if JQL requires it: anything that is not a bare identifier, and any bare
     * identifier that happens to be a reserved word. The {@code cf[10004]} form is left alone, since it is
     * valid JQL and quoting it would break it.
     *
     * @param raw the field name as a human writes it
     * @return the name as it must appear in JQL
     */
    protected static String renderName(String raw) {
        if (raw.matches("cf\\[\\d+]")) {
            return raw;
        }
        boolean bareIdentifier = raw.matches("[A-Za-z][A-Za-z0-9_]*");
        if (bareIdentifier && !RESERVED_WORDS.contains(raw.toLowerCase(Locale.ROOT))) {
            return raw;
        }
        return JqlValue.quote(raw);
    }

    /** The field name as it appears in JQL, quoted if it had to be. */
    public String name() {
        return name;
    }

    /** {@code field IS EMPTY} - the field has no value. */
    public JqlClause isEmpty() {
        return JqlClause.raw(name + " IS EMPTY");
    }

    /** {@code field IS NOT EMPTY} - the field has some value. */
    public JqlClause isNotEmpty() {
        return JqlClause.raw(name + " IS NOT EMPTY");
    }

    /** An ascending {@code ORDER BY} term on this field. */
    public JqlOrderTerm asc() {
        return new JqlOrderTerm(name, JqlOrderTerm.Direction.ASC);
    }

    /** A descending {@code ORDER BY} term on this field. */
    public JqlOrderTerm desc() {
        return new JqlOrderTerm(name, JqlOrderTerm.Direction.DESC);
    }

    /**
     * A clause with an operator this class does not model, for a field type a marketplace app introduced.
     *
     * @param operator the JQL operator, for example {@code ~=}
     * @param value the right-hand side, escaped
     * @return the clause
     */
    public JqlClause operator(String operator, JqlValue value) {
        return JqlClause.raw(name + " " + operator + " " + value.render());
    }

    /** Builds {@code name op value}. */
    JqlClause binary(String operator, JqlValue value) {
        return JqlClause.raw(name + " " + operator + " " + value.render());
    }

    /** Builds {@code name op (v1, v2, ...)}, rejecting an empty list, which JQL cannot express. */
    JqlClause list(String operator, java.util.List<JqlValue> values) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "A JQL '" + operator + "' on " + name + " needs at least one value; "
                            + "an empty IN list is not valid JQL and would silently match nothing");
        }
        String rendered = values.stream().map(JqlValue::render).collect(java.util.stream.Collectors.joining(", "));
        return JqlClause.raw(name + " " + operator + " (" + rendered + ")");
    }

    @Override
    public String toString() {
        return name;
    }
}
