package ru.ludwigandreas.security.operation;

import java.util.regex.Pattern;
import ru.ludwigandreas.security.data.DataAction;

/**
 * One thing a caller can do, as code names it and configuration grants it.
 *
 * <p>The id is {@code area.object.verb} - {@code outbox.message.discard}, {@code catalog.product.update}. Code
 * authorizes by this id and never by a role; which roles hold it, and with which row scope, is the deployment's
 * {@code ludwig.security.roles}.
 *
 * <p>An operation that touches stored rows names its resource and data action, and its scope then filters those
 * rows. One that touches none admits only {@code ALL} or {@code OBJECT}.
 *
 * @param id             {@code area.object.verb}
 * @param sensitivity    what it can do
 * @param surface        where it is served
 * @param resource       the data-scope resource its rows belong to, or null
 * @param action         the data action on that resource, or null when there is no resource
 * @param descriptionKey the i18n key of its description
 */
public record Operation(String id, Sensitivity sensitivity, Surface surface, String resource, String action,
                        String descriptionKey) {

    /** The data-scope action of an operation that names no resource: invoking it. */
    public static final String INVOKE = "invoke";

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){2}");

    /**
     * Validates the declaration.
     */
    public Operation {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Operation id '" + id + "' is not area.object.verb: three"
                    + " lower-case segments of letters and digits, each starting with a letter");
        }
        if (sensitivity == null || surface == null) {
            throw new IllegalArgumentException("Operation '" + id + "' needs a sensitivity and a surface");
        }
        if (descriptionKey == null || descriptionKey.isBlank()) {
            throw new IllegalArgumentException("Operation '" + id + "' needs a description key");
        }
        if ((resource == null) != (action == null) || resource != null && (resource.isBlank() || action.isBlank())) {
            throw new IllegalArgumentException("Operation '" + id + "' names a resource and an action together, or"
                    + " neither");
        }
        if (action != null && (sensitivity == Sensitivity.READ) != DataAction.READ.equals(action)) {
            throw new IllegalArgumentException("Operation '" + id + "' is " + sensitivity + " but acts '" + action
                    + "' on " + resource + ": a READ operation reads, and only a READ operation does");
        }
    }

    /**
     * An operation of the service's own API.
     *
     * @param id             {@code area.object.verb}
     * @param sensitivity    what it can do
     * @param descriptionKey its description's i18n key
     * @return the operation, touching no rows until {@link #on} names them
     */
    public static Operation business(String id, Sensitivity sensitivity, String descriptionKey) {
        return new Operation(id, sensitivity, Surface.BUSINESS, null, null, descriptionKey);
    }

    /**
     * An operation of the administrative surface.
     *
     * @param id             {@code area.object.verb}
     * @param sensitivity    what it can do
     * @param descriptionKey its description's i18n key
     * @return the operation, touching no rows until {@link #on} names them
     */
    public static Operation admin(String id, Sensitivity sensitivity, String descriptionKey) {
        return new Operation(id, sensitivity, Surface.ADMIN, null, null, descriptionKey);
    }

    /**
     * The same operation, touching the rows of a data-scope resource.
     *
     * @param resourceType the resource, as its {@code DataScopeMapping} is registered
     * @param dataAction   the action on it
     * @return the operation
     */
    public Operation on(String resourceType, String dataAction) {
        return new Operation(id, sensitivity, surface, resourceType, dataAction, descriptionKey);
    }

    /**
     * The first segment of the id.
     *
     * @return the area
     */
    public String area() {
        return id.substring(0, id.indexOf('.'));
    }

    /**
     * Whether its scope filters stored rows.
     *
     * @return true when it names a resource
     */
    public boolean touchesRows() {
        return resource != null;
    }

    /**
     * The resource a per-subject grant for this operation is keyed by: its own resource, or for one that touches no
     * rows, its id.
     *
     * @return the key
     */
    public String scopeResource() {
        return resource == null ? id : resource;
    }

    /**
     * The action a per-subject grant for this operation is keyed by: its data action, or {@link #INVOKE}.
     *
     * @return the key
     */
    public String scopeAction() {
        return action == null ? INVOKE : action;
    }
}
