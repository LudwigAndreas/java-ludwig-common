package ru.ludwigandreas.export.web;

import java.time.ZoneId;
import java.util.Locale;
import org.springframework.context.i18n.LocaleContextHolder;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * The caller, as the platform already identifies them.
 *
 * <p>The subject comes from {@code security-spring-boot-starter}'s principal and the locale from
 * {@code web-core}'s {@code Accept-Language} resolution, so a report request is attributed and
 * translated exactly like every other request in the service. Nothing here is report-specific, which
 * is the point: a module that resolved its own caller would be a second answer to "who is this", and
 * the two would differ in the cases that matter.
 *
 * <p>The timezone defaults to UTC rather than to the server's. A report is read by somebody who is
 * not on this machine, and a file whose timestamps silently followed the container's zone would be
 * right in staging and an hour out in production.
 */
public class SecurityReportCaller implements ReportCaller {

    @Override
    public String principalId() {
        return SecurityPrincipals.require().subject();
    }

    @Override
    public Locale locale() {
        return LocaleContextHolder.getLocale();
    }

    @Override
    public ZoneId zone() {
        return ZoneId.of("UTC");
    }
}
