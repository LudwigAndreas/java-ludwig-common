package ru.ludwigandreas.notification.security;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * This service's business operations: what code authorizes and scopes rows by. Which roles hold each, with what
 * scope, is {@code ludwig.security.roles} in {@code application.yml}.
 *
 * <p>Outside {@code config}, which is the composition root: the service and repository layers name these
 * operations, and importing back out of the composition root would be the cycle the architecture rules reject.
 */
public final class NotificationOperations implements OperationContributor {

    /** The resource the delivery operations scope; its scope mapping is registered under this name. */
    public static final String DELIVERY_RESOURCE = "notification-delivery";

    /** The resource the request operation scopes; its scope mapping is registered under this name. */
    public static final String REQUEST_RESOURCE = "notification-request";

    /** See deliveries and their state history. */
    public static final String DELIVERY_READ = "notification.delivery.read";

    /** Send a failed delivery again. */
    public static final String DELIVERY_RETRY = "notification.delivery.retry";

    /** Stop a delivery that has not been sent. */
    public static final String DELIVERY_CANCEL = "notification.delivery.cancel";

    /** See a delivery's rendered content: a read of its own, because the content names the recipient. */
    public static final String CONTENT_READ = "notification.content.read";

    /** See a submitted notification request. */
    public static final String REQUEST_READ = "notification.request.read";

    private static final String KEY = "notification.operation.";

    @Override
    public Collection<Operation> operations() {
        return List.of(
                Operation.business(DELIVERY_READ, Sensitivity.READ, KEY + DELIVERY_READ)
                        .on(DELIVERY_RESOURCE, DataAction.READ),
                Operation.business(DELIVERY_RETRY, Sensitivity.CHANGE, KEY + DELIVERY_RETRY)
                        .on(DELIVERY_RESOURCE, DataAction.WRITE),
                Operation.business(DELIVERY_CANCEL, Sensitivity.CHANGE, KEY + DELIVERY_CANCEL)
                        .on(DELIVERY_RESOURCE, DataAction.WRITE),
                Operation.business(CONTENT_READ, Sensitivity.READ, KEY + CONTENT_READ)
                        .on(DELIVERY_RESOURCE, DataAction.READ),
                Operation.business(REQUEST_READ, Sensitivity.READ, KEY + REQUEST_READ)
                        .on(REQUEST_RESOURCE, DataAction.READ));
    }
}
