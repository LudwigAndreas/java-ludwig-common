package ru.ludwigandreas.jira.jql;

/**
 * A JQL field searched through Jira's text index: summary, description, environment, comment, and the
 * synthetic {@code text} field that spans all of them.
 *
 * <p>The operator is {@code ~}, and it is not {@code LIKE}. It runs the value through Lucene, which means
 * it tokenizes, applies stemming and ignores stop words: {@code summary ~ "running"} matches an issue
 * titled "run", and {@code summary ~ "the"} matches nothing at all. For a literal substring match there is
 * no JQL operator - that is a limitation of Jira, not of this builder.
 *
 * <p>{@link #matchesPhrase(String)} wraps the value in the inner quotes Lucene needs for a phrase query,
 * which is the closest JQL gets to "these words, in this order".
 */
public final class TextJqlField extends JqlField {

    /**
     * Creates a reference to a field of this kind.
     *
     * @param name the field's JQL name
     */
    public TextJqlField(String name) {
        super(name);
    }

    /** {@code field ~ "text"} - matches the indexed terms of the value. */
    public JqlClause contains(String text) {
        return binary("~", JqlValue.of(text));
    }

    /** {@code field !~ "text"}. */
    public JqlClause doesNotContain(String text) {
        return binary("!~", JqlValue.of(text));
    }

    /**
     * {@code field ~ "\"exact phrase\""} - a Lucene phrase query, matching the terms adjacently and in
     * order.
     *
     * @param phrase the phrase to match
     * @return the clause
     */
    public JqlClause matchesPhrase(String phrase) {
        return binary("~", JqlValue.of("\"" + phrase + "\""));
    }

    /** {@code field = "text"} - exact match on the stored value rather than the index. */
    public JqlClause is(String text) {
        return binary("=", JqlValue.of(text));
    }

    /** {@code field != "text"}. */
    public JqlClause isNot(String text) {
        return binary("!=", JqlValue.of(text));
    }
}
