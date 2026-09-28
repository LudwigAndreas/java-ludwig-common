package ru.ludwigandreas.example.catalog.client.dto;

import java.math.BigDecimal;

/**
 * A supplier, as the supplier directory describes it.
 *
 * <p>A subset again, for the reason given on {@link NotificationAccepted}: the directory also
 * publishes addresses, contacts, contract references and a payment profile, and a catalogue report
 * has no business carrying any of them. Declaring only what the report writes is also what keeps the
 * enrichment payload small - at a million rows the difference between four fields and forty is the
 * difference between a partner response that fits in a window's memory budget and one that does not.
 *
 * @param id          the supplier's identifier, matching {@code ProductEntity.supplierPartnerId}
 * @param name        the display name, written into the report
 * @param ratingClass the directory's own quality band, written into the report
 * @param onTimeRate  the fraction of orders delivered on time, as a value between 0 and 1 - a
 *                    fraction rather than a percentage because the report renders it as a percent
 *                    cell, and a number that is already multiplied by a hundred renders as 8500%
 */
public record SupplierSummary(String id, String name, String ratingClass, BigDecimal onTimeRate) {
}
