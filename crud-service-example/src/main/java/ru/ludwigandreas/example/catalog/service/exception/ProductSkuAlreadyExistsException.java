package ru.ludwigandreas.example.catalog.service.exception;

import ru.ludwigandreas.example.catalog.support.i18n.LocalizedException;
import ru.ludwigandreas.example.catalog.support.i18n.ProblemStatus;

/** Another product already owns this SKU. Rendered as HTTP 409. */
public class ProductSkuAlreadyExistsException extends LocalizedException {

    public ProductSkuAlreadyExistsException(String sku) {
        super(ProblemStatus.CONFLICT, "error.product.sku-exists", sku);
    }
}
