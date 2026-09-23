package ru.ludwigandreas.jira.api;

/**
 * The REST namespaces this client speaks.
 *
 * <p>{@code /rest/api/2} rather than {@code /rest/api/latest}: {@code latest} resolves to whatever the
 * instance considers newest, so the same code talks to a different API version after a Jira upgrade and
 * the change shows up as a deserialization failure in production. Pinning the version means an upgrade
 * changes nothing until someone decides it should.
 *
 * <p>{@code /rest/api/3} is deliberately absent - it is a Jira Cloud API and does not exist on Server 9.12.
 */
public final class ApiPaths {

    /** The Jira Platform REST API, version 2. */
    public static final String API_2 = "/rest/api/2";

    /** The ALM Works Structure REST API, version 2.0. */
    public static final String STRUCTURE_2 = "/rest/structure/2.0";

    private ApiPaths() {
    }
}
