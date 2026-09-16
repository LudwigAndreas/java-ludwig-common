package ru.ludwigandreas.webcore.integration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Constraints whose messages come from the application's own bundle, by key. */
public record TestThingRequest(
        @NotBlank(message = "{test.validation.name.required}")
        @Size(max = 8, message = "{test.validation.name.size}")
        String name,
        @Min(value = 0, message = "{test.validation.quantity.min}")
        int quantity) {
}
