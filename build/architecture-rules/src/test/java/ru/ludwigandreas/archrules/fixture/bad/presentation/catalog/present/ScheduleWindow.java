package ru.ludwigandreas.archrules.fixture.bad.presentation.catalog.present;

import java.time.ZoneId;

/**
 * A zone with no locale, which is not a violation either.
 *
 * <p>A scheduling concern rather than a presentation one. The second rule requires <em>both</em>
 * fields precisely so that this, and the mirror case of a locale with no zone, stay legal: a module
 * that schedules work in a named zone is not restating anything.
 */
public record ScheduleWindow(String cron, ZoneId zone) {
}
