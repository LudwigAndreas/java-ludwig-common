package ru.ludwigandreas.reconciliation.integration.testmodel;

import java.time.Instant;

/**
 * The external record the test partner returns.
 *
 * @param orderId   the correlation key
 * @param status    the status the partner holds
 * @param changedAt when the partner says it last changed, which is what stale-write protection reads
 */
public record BillingRecord(String orderId, String status, Instant changedAt) {
}
