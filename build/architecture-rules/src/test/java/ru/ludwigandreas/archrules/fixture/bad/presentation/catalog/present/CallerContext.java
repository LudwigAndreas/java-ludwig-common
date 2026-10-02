package ru.ludwigandreas.archrules.fixture.bad.presentation.catalog.present;

import java.time.ZoneId;
import java.util.Locale;

/**
 * A second caller-preference type, invented locally because this module needed the pair.
 *
 * <p>The shape the second rule exists to catch, and like the restated operation vocabulary it is
 * worth noting how reasonable it looks: a two-field record with no behaviour and an obvious name.
 * The only thing wrong with it is that the platform already has one, with a precedence order this
 * one does not have.
 */
public record CallerContext(Locale locale, ZoneId zone) {
}
