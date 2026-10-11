package ru.ludwigandreas.reconciliation.integration.testmodel;

import java.time.Instant;

/**
 * What the stub partner lists under a scope.
 *
 * @param id        the partner's stable id
 * @param title     the item's content
 * @param changedAt when the partner last changed it; null for a partner that publishes no timestamps
 */
public record PartnerItem(String id, String title, Instant changedAt) {
}
