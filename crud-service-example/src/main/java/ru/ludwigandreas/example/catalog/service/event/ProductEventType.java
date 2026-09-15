package ru.ludwigandreas.example.catalog.service.event;

/** Event types published for the {@code Product} aggregate. */
public final class ProductEventType {

    public static final String CREATED = "ProductCreated";
    public static final String UPDATED = "ProductUpdated";
    public static final String DELETED = "ProductDeleted";

    private ProductEventType() {
    }
}
