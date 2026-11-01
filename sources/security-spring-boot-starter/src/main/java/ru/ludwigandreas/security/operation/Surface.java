package ru.ludwigandreas.security.operation;

/** Where an operation is served, which decides the caller rules that apply beyond holding it. */
public enum Surface {

    /** The service's own API. */
    BUSINESS,

    /**
     * The administrative surface under {@code /admin/v1/}: partners are refused, people may be required to arrive
     * through an acting party, and a privileged operation is eligible for four-eyes approval.
     */
    ADMIN
}
