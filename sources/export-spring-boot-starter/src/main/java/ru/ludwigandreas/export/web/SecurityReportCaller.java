package ru.ludwigandreas.export.web;

import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * The caller, as the platform already identifies them.
 *
 * <p>The subject comes from {@code security-spring-boot-starter}'s principal and the presentation
 * pair from {@code web-core}'s caller-preference contract, so a report request is attributed,
 * translated and dated exactly like every other request in the service. Nothing here is
 * report-specific, which is the point: a module that resolved its own caller would be a second
 * answer to "who is this", and the two would differ in the cases that matter.
 *
 * <h2>The zone used to be a literal, and that was wrong</h2>
 *
 * <p>This method returned {@code ZoneId.of("UTC")} with a comment arguing - correctly - that the
 * server's zone would be worse. Both halves of that were true and the conclusion was still a report
 * five hours out on every row for a user in Yekaterinburg, because at the time there was nowhere to
 * ask what zone the caller actually reads times in. There is now, so it asks.
 *
 * <p>UTC remains the answer when nothing else resolves: it is the configured default of the
 * preference chain, which puts the choice in the deployment's configuration instead of in this
 * file.
 */
public class SecurityReportCaller implements ReportCaller {

    @Override
    public String principalId() {
        return SecurityPrincipals.require().subject();
    }

    @Override
    public UserPreferences preferences() {
        return UserPreferences.current();
    }
}
