package ru.ludwigandreas.fileaction.unit;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The row record the binding tests bind to. A record, as every row type must be.
 *
 * @param sku      the article code
 * @param quantity how many
 * @param price    unit price
 * @param dueDate  when it is wanted
 * @param comment  free text, optional in every binding that declares it
 */
public record OrderLine(String sku, Integer quantity, BigDecimal price, LocalDate dueDate,
                        String comment) {
}
