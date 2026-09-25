package ru.ludwigandreas.storage.exception;

import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Nothing is stored at that location.
 *
 * <p>Its own type rather than a flag on {@link ObjectStoreException} because the two call for
 * opposite responses and are distinguished at almost every call site: a missing object is usually
 * expected - a sentinel file that has not arrived, an archived copy that was already moved - and a
 * store that cannot be reached never is. Collapsing them would mean every caller that wants to treat
 * absence as normal has to inspect a message or a status code to find out whether the bucket is down,
 * and the first one to get that wrong turns an outage into a silently skipped run.
 *
 * <p>Deliberately <em>not</em> thrown by {@link ru.ludwigandreas.storage.api.ObjectStore#delete}: see
 * that method for why absence is a success there.
 */
public class ObjectNotFoundException extends ObjectStoreException {

    private static final long serialVersionUID = 1L;

    /**
     * A location with nothing at it.
     *
     * @param uri   the location
     * @param cause what the store threw, or {@code null} when absence was established without one
     */
    public ObjectNotFoundException(String uri, Throwable cause) {
        super(ProblemStatus.NOT_FOUND, StorageProblemCodes.OBJECT_NOT_FOUND, cause, uri);
        withProperty("uri", uri);
    }
}
