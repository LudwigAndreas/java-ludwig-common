package ru.ludwigandreas.notification.repository.entity;

/**
 * Which lane a delivery is claimed in.
 *
 * <p>Stored as an {@code int} rather than as an enum name ({@code @Enumerated(ORDINAL)} is not used
 * either) because the claim query orders and filters on it: {@code ORDER BY priority DESC} on a
 * {@code varchar} would sort {@code 'HIGH'} after {@code 'BULK'} alphabetically, which is the wrong
 * answer and a silent one. The {@link #getWeight()} column is what the index is built on.
 */
public enum DeliveryPriority {

    /** Password resets, one-time codes, security alerts. Claimed first, in its own reserved budget. */
    HIGH(100),

    /** Ordinary transactional traffic. */
    NORMAL(50),

    /** Campaigns and digests. Never allowed to consume the whole batch - see the poller. */
    BULK(10);

    private final int weight;

    DeliveryPriority(int weight) {
        this.weight = weight;
    }

    /** The stored, orderable value. Higher is claimed first. */
    public int getWeight() {
        return weight;
    }

    /**
     * The constant with this stored weight.
     *
     * @throws IllegalArgumentException if no constant carries it, which can only mean a row written
     *                                  by a newer version of this service
     */
    public static DeliveryPriority ofWeight(int weight) {
        for (DeliveryPriority priority : values()) {
            if (priority.weight == weight) {
                return priority;
            }
        }
        throw new IllegalArgumentException("No DeliveryPriority with weight " + weight);
    }
}
