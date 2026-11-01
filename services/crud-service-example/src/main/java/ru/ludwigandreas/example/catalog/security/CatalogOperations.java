package ru.ludwigandreas.example.catalog.security;

import java.util.Collection;
import java.util.List;
import ru.ludwigandreas.security.data.DataAction;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.Sensitivity;

/**
 * The catalog's business operations: what code authorizes and scopes rows by. Which roles hold each, with what
 * scope, is {@code ludwig.security.roles} in {@code application.yml}.
 *
 * <p>Outside {@code config}, which is the composition root: the service and repository layers name these
 * operations, and importing back out of the composition root would be the cycle the architecture rules reject.
 */
public final class CatalogOperations implements OperationContributor {

    /** The resource the product operations scope; its scope mapping is registered under this name. */
    public static final String PRODUCT_RESOURCE = "product";

    /** See products: the paged search, one product, and the catalog export. */
    public static final String PRODUCT_READ = "catalog.product.read";

    /** Change a product. */
    public static final String PRODUCT_UPDATE = "catalog.product.update";

    /** Delete a product. */
    public static final String PRODUCT_DELETE = "catalog.product.delete";

    private static final String KEY = "catalog.operation.";

    @Override
    public Collection<Operation> operations() {
        return List.of(
                Operation.business(PRODUCT_READ, Sensitivity.READ, KEY + PRODUCT_READ)
                        .on(PRODUCT_RESOURCE, DataAction.READ),
                Operation.business(PRODUCT_UPDATE, Sensitivity.CHANGE, KEY + PRODUCT_UPDATE)
                        .on(PRODUCT_RESOURCE, DataAction.WRITE),
                Operation.business(PRODUCT_DELETE, Sensitivity.CHANGE, KEY + PRODUCT_DELETE)
                        .on(PRODUCT_RESOURCE, DataAction.DELETE));
    }
}
