package ru.ludwigandreas.odatafilter.exception;

/**
 * A query option carried a value of the wrong shape - {@code $top=abc}, {@code $count=yes}. Maps to
 * HTTP 400 and names the option in a {@code property} member, so a client can see which of the five
 * it got wrong without parsing a sentence.
 *
 * <p>Separate from {@link FilterSyntaxException}, which is about the <em>content</em> of a
 * {@code $filter} or {@code $orderby} expression. Conflating them told a caller with a typo in
 * {@code $count} that its filter expression could not be parsed.
 *
 * <p>The offending value is deliberately not in the message. It is the caller's own input and the
 * caller already has it, and this message reaches the service's logs.
 */
public class InvalidQueryOptionException extends ODataFilterException {

    private final String option;
    private final String expected;

    public InvalidQueryOptionException(String option, String expected) {
        super("%s must be %s".formatted(option, expected));
        this.option = option;
        this.expected = expected;
    }

    /** The query option that was wrong, with its {@code $} prefix - e.g. {@code $count}. */
    public String option() {
        return option;
    }

    /** What the option accepts, for the message's {@code {0}} placeholder - e.g. "true or false". */
    public String expected() {
        return expected;
    }
}
