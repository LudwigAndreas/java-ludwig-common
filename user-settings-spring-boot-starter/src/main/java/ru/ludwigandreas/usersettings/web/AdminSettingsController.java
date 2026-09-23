package ru.ludwigandreas.usersettings.web;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.api.SettingsWriter;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;
import ru.ludwigandreas.usersettings.resolve.SettingsSubjects;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;
import ru.ludwigandreas.usersettings.web.dto.ConsentStateResponse;
import ru.ludwigandreas.usersettings.web.dto.SettingsResponse;
import ru.ludwigandreas.usersettings.web.dto.UpdateSettingsRequest;

/**
 * Administrative access to other subjects' settings, and to the role and tenant layers.
 *
 * <p>Mounted only when {@code ludwig.user-settings.web.enabled=true} <em>and</em> the service runs in
 * owner mode, because half of it writes. It is a separate controller from the self-service one
 * specifically so that a deployment can have the second without the first.
 *
 * <p><b>The tenant is never taken from the request.</b> Every subject here is resolved through the
 * same {@link SettingsTenantResolver} the rest of the module uses, which answers with the
 * <em>caller's</em> tenant - so an administrator naming a subject in another organization gets a
 * lookup scoped to their own tenant, finds nothing, and is refused by the access policy. Accepting a
 * tenant parameter would make that a matter of checking rather than a matter of construction.
 *
 * <p>PII-flagged values are redacted in every response here. An administrative screen has no
 * business displaying a person's private values, and the read is audited either way.
 */
@RestController
@RequiredArgsConstructor
public class AdminSettingsController {

    private final SettingsLookup lookup;
    private final SettingsWriter writer;
    private final ConsentService consents;
    private final SettingsResponseRenderer mapper;
    private final SettingDefinitionRegistry registry;
    private final SettingsTenantResolver tenantResolver;

    @GetMapping("${ludwig.user-settings.web.admin-base-path:/admin/settings}/subjects/{subject}")
    public SettingsResponse settingsOf(@PathVariable String subject) {
        return mapper.toResponse(lookup.getAll(PrincipalRef.user(subject)), true);
    }

    /**
     * Sets values at one scope.
     *
     * <p>{@code scopeType} is a {@link SettingLayer}, so this one endpoint serves a user-level
     * correction, a role default and a tenant default. The two layers that are not stored - the
     * platform layer, which is configuration, and the definition default, which is code - are
     * rejected by the writer with a message saying where they actually live.
     */
    @PutMapping("${ludwig.user-settings.web.admin-base-path:/admin/settings}/scopes/{scopeType}/{scopeId}")
    public void setForScope(@PathVariable SettingLayer scopeType,
                            @PathVariable String scopeId,
                            @RequestBody UpdateSettingsRequest request) {
        SettingsSubject subject = callerSubject();
        SettingScope scope = new SettingScope(scopeType, scopeId);
        request.values().forEach((key, raw) -> apply(subject, scope, registry.require(key), raw));
    }

    @GetMapping("${ludwig.user-settings.web.admin-base-path:/admin/settings}/subjects/{subject}/consents")
    public ConsentStateResponse consentsOf(@PathVariable String subject) {
        return mapper.toResponse(consents.history(PrincipalRef.user(subject), null));
    }

    /**
     * The subject's consent state as it stood at an instant - "prove what they consented to on date
     * X", which is the question a regulator asks and a current-state column cannot answer.
     */
    @GetMapping("${ludwig.user-settings.web.admin-base-path:/admin/settings}/subjects/{subject}/consents/as-of")
    public ConsentStateResponse consentsAsOf(
            @PathVariable String subject,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return mapper.toResponse(consents.stateAsOf(PrincipalRef.user(subject), at));
    }

    /** Captures the definition's type so the converter and the write agree on it. */
    private <T> void apply(SettingsSubject subject, SettingScope scope,
                           SettingDefinition<T> definition, String raw) {
        if (raw == null) {
            writer.resetForScope(subject, scope, definition);
            return;
        }
        SettingValueConverter<T> converter = registry.converterFor(definition);
        writer.setForScope(subject, scope, definition, converter.fromStorage(raw));
    }

    /**
     * The administrator themselves, which is what fixes the tenant these writes are confined to. A
     * role- or tenant-scoped write is not "about" any subject, so the subject here is the caller.
     */
    private SettingsSubject callerSubject() {
        LudwigPrincipal principal = SecurityPrincipals.require();
        return SettingsSubjects.require(tenantResolver,
                new PrincipalRef(principal.type(), principal.subject()));
    }
}
