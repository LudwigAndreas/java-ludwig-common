/**
 * The typed configuration, in its own package rather than in {@code config}.
 *
 * <p>Not a stylistic choice: with the properties in {@code config}, {@code config} depended on
 * {@code consumer} - the autoconfiguration constructs the container factory builder - and
 * {@code consumer} depended on {@code config}, because the builder reads the settings. That is a package
 * cycle, and this module's own architecture test found it. Splitting the settings out breaks it in the
 * direction that makes sense: the configuration and the builder both read the settings, and the settings
 * read nothing.
 *
 * <p>The name follows {@code notification-service}'s {@code settings} package, which holds
 * {@code NotificationProperties} for the same reason.
 */
package ru.ludwigandreas.messaging.settings;
