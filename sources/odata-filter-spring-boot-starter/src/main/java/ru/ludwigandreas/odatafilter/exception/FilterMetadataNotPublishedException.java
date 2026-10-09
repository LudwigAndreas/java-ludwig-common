package ru.ludwigandreas.odatafilter.exception;

/**
 * Nothing publishes a filter policy under the requested name. Maps to HTTP 404.
 *
 * <p>404 and not an empty document: an empty document would confirm that an entity by that name exists and
 * merely publishes nothing, which turns the discovery endpoint into a way to enumerate the entity model.
 * This is the same conflation the module already makes between "no such field" and "that field exists but
 * is not filterable", for the same reason.
 *
 * <p>The requested name <em>is</em> in the message, unlike the values in
 * {@link InvalidQueryOptionException}: a published name is a URL segment the caller chose and an identifier
 * the application declared, not data about a person.
 */
public class FilterMetadataNotPublishedException extends ODataFilterException {

    private final String requestedName;

    public FilterMetadataNotPublishedException(String requestedName) {
        super("No filter policy is published as '%s'".formatted(requestedName));
        this.requestedName = requestedName;
    }

    /** The name the caller asked for. */
    public String requestedName() {
        return requestedName;
    }
}
