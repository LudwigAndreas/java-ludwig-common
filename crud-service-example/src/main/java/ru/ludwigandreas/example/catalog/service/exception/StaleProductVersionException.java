package ru.ludwigandreas.example.catalog.service.exception;

import java.util.UUID;
import ru.ludwigandreas.example.catalog.support.i18n.LocalizedException;
import ru.ludwigandreas.example.catalog.support.i18n.ProblemStatus;

/**
 * The client sent an update based on a product version that has since been superseded. Rendered as
 * HTTP 409 - the caller has to re-read the product and re-apply its change.
 */
public class StaleProductVersionException extends LocalizedException {

    public StaleProductVersionException(UUID id, long expectedVersion, long actualVersion) {
        super(ProblemStatus.CONFLICT, "error.product.stale-version", id, expectedVersion, actualVersion);
    }
}
