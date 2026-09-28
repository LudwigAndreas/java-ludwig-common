package ru.ludwigandreas.notification.service.model;

/**
 * Lifecycle of one delivery as the business layer knows it. Twin of
 * {@code repository.entity.DeliveryStatus} and {@code web.dto.DeliveryStatusDto}.
 */
public enum DeliveryState {
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
