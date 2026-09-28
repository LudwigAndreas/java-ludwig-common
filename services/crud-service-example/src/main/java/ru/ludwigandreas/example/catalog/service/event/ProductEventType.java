package ru.ludwigandreas.example.catalog.service.event;

/** Event types published for the {@code Product} aggregate. */
public final class ProductEventType {

    public static final String CREATED = "ProductCreated";
    public static final String UPDATED = "ProductUpdated";
    public static final String DELETED = "ProductDeleted";

    /**
     * An instruction to notify the catalogue stewards, not a domain fact.
     *
     * <p>Named differently from {@link #CREATED} on purpose. The two rows describe the same change,
     * but one is published to whoever subscribes and the other is addressed to one named peer - and
     * an operator reading the outbox table should be able to tell which is which without looking at
     * the transport column.
     */
    public static final String NOTIFY_CREATED = "NotifyProductCreated";

    private ProductEventType() {
    }
}
