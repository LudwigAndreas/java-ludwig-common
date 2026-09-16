package ru.ludwigandreas.example.catalog.service.exception;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/** Another product already owns this SKU. Rendered as HTTP 409. */
public class ProductSkuAlreadyExistsException extends LocalizedException {

    public ProductSkuAlreadyExistsException(String sku) {
        super(ProblemStatus.CONFLICT, "error.product.sku-exists", sku);
    }
}
