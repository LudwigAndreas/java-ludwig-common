package ru.ludwigandreas.notification.service.model;

/**
 * The transports the business layer knows about.
 *
 * <p>Its persistence twin is {@code repository.entity.ChannelKind} and its wire twin is
 * {@code web.dto.ChannelTypeDto}; MapStruct maps between them by constant name at compile time, so
 * adding a channel to one without the others fails the build rather than a request.
 */
public enum ChannelType {
    EMAIL,
    CHAT,
    WEBHOOK
}
