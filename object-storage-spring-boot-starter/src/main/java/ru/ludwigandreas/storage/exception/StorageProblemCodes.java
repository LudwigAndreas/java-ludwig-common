package ru.ludwigandreas.storage.exception;

/**
 * The problem codes this module emits, and therefore the ones a caller may branch on.
 *
 * <p>Constants rather than literals at the throw sites, for the reason the export starter's
 * equivalent gives: a code is published in the response body and is part of the API contract, so a
 * client branches on {@code code} instead of parsing a translated {@code detail}. The keys in
 * {@code i18n/ludwig-storage-messages.properties} are exactly these values plus a {@code .title}
 * sibling for each, in both locales; Checkstyle's {@code Translation} check makes drift between the
 * locales a build failure.
 */
public final class StorageProblemCodes {

    /** Namespace prefix for every code below, so a caller can spot a storage error at a glance. */
    public static final String PREFIX = "ludwig.storage.error.";

    /** Nothing is stored at the requested location. */
    public static final String OBJECT_NOT_FOUND = PREFIX + "object-not-found";

    /** The location is not a form this platform serves; carries the offending string. */
    public static final String INVALID_URI = PREFIX + "invalid-uri";

    /** The store could not be reached, or refused the request; carries the operation and location. */
    public static final String STORE_UNAVAILABLE = PREFIX + "store-unavailable";

    /** The credentials resolved, and the store says they do not permit this; carries the location. */
    public static final String ACCESS_DENIED = PREFIX + "access-denied";

    private StorageProblemCodes() {
    }
}
