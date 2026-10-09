package ru.ludwigandreas.notification.repository.entity;

/**
 * How an announcement's audience is described, as stored.
 *
 * <p>An enum rather than a free string because a database check constraint pairs it with the
 * presence of {@code audience_value} - a {@code ROLE} needs one and {@code EVERYONE} must not have
 * one - and because the visibility predicate branches on it. Adding a kind is adding a constant
 * here, its service-layer and wire twins, and one branch to that predicate.
 *
 * <p>Named to match {@code ChannelKind}'s relationship to {@code ChannelType}: the service-layer
 * twin is {@code service.model.AudienceType}.
 */
public enum AudienceKind {

    /** Every user. {@code audience_value} is null. */
    EVERYONE,

    /** Everybody who currently holds the role named in {@code audience_value}. */
    ROLE
}
