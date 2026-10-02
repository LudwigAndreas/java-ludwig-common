package ru.ludwigandreas.example.catalog.service.fileaction;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * One row of a product import spreadsheet.
 *
 * <h2>Why the constraints are here and not in the handler</h2>
 *
 * <p>The engine runs Bean Validation over every bound row before the handler sees it, so a blank SKU or a negative
 * price is a <em>cell-level reject</em> with the column's heading in the message - which is what the user needs -
 * rather than an exception from somewhere inside the domain. A handler that checked these itself would produce a
 * worse message and would run after the row had already been counted as bound.
 *
 * <p>What the handler is left with is the thing validation cannot know: whether the category exists and whether
 * the SKU is already taken. Those need the database.
 *
 * @param sku         the article code, which must be unique in the catalogue
 * @param name        the product name
 * @param description free text
 * @param price       the sale price
 * @param stock       how many are in stock
 * @param category    the category's name, resolved to an id by the handler
 */
public record ProductImportRow(
        @NotBlank @Size(max = 64) String sku,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @Positive BigDecimal price,
        @PositiveOrZero Integer stock,
        @NotBlank String category) {
}
