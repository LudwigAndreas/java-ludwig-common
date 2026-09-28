package ru.ludwigandreas.notification.service.model;

/**
 * Which lane a notification is delivered in, as the business layer names it.
 *
 * <p>Two lanes would satisfy the requirement; three is what the traffic actually looks like.
 * {@link #HIGH} is what a person is waiting for with a screen open, {@link #NORMAL} is ordinary
 * transactional traffic, and {@link #BULK} is a campaign that nobody notices being ten minutes late.
 * Merging the last two would mean a newsletter and an order confirmation competing for the same
 * budget, which is the starvation case the lanes exist to prevent.
 */
public enum Priority {
    HIGH,
    NORMAL,
    BULK
}
