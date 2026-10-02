package ru.ludwigandreas.fileaction.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * The row the integration tests import.
 *
 * <p>Carries Bean Validation annotations, because that is how a real binding states what a row must be and the
 * engine runs the validator over every bound row - so a test whose rows carried no constraints would not exercise
 * the path most imports depend on.
 *
 * @param sku      the article code, which must be present
 * @param quantity how many, which must be positive
 * @param note     free text
 */
public record OrderLineRow(@NotBlank String sku, @Positive Integer quantity, String note) {
}
