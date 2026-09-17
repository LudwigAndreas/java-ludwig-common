package ru.ludwigandreas.notification.web.dto;

/**
 * Delivery lifecycle as it appears on the wire.
 *
 * <p>Its service twin is {@code service.model.DeliveryState} and its persistence twin is
 * {@code repository.entity.DeliveryStatus}; MapStruct maps between them by constant name, so adding
 * a state to one without the others fails the build rather than a request.
 */
public enum DeliveryStatusDto {
    ACCEPTED,
    PENDING,
    CLAIMED,
    SENT,
    DELIVERED,
    FAILED,
    DEAD,
    SUPPRESSED,
    CANCELLED,
    BATCHED,
    COLLAPSED
}
