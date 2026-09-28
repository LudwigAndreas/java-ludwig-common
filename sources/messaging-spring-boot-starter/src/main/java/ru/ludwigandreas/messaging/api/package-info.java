/**
 * The wire contract both halves of the platform's messaging share.
 *
 * <p>{@link ru.ludwigandreas.messaging.api.MessageHeaders} and
 * {@link ru.ludwigandreas.messaging.api.InboundEnvelope} are what
 * {@code outbox-spring-boot-starter} depends on this module for, and they are deliberately free of
 * Spring: a header name is a string and an envelope is a record, and keeping them that way is what
 * makes the {@code messaging-core} split described in this module's POM a move rather than a rewrite,
 * should the dependency edge ever have to reverse.
 */
package ru.ludwigandreas.messaging.api;
