package ru.ludwigandreas.usersettings.web;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillRequest;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillResult;
import ru.ludwigandreas.usersettings.backfill.SettingsBackfillService;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;
import ru.ludwigandreas.usersettings.resolve.SettingsSubjects;
import ru.ludwigandreas.usersettings.resolve.SettingsTenantResolver;
import ru.ludwigandreas.usersettings.web.dto.BackfillRequest;
import ru.ludwigandreas.usersettings.web.dto.BackfillResponse;

/**
 * Republishes stored state so a new or rebuilt projection can be seeded.
 *
 * <p>Mounted only when {@code ludwig.user-settings.web.enabled=true} <em>and</em>
 * {@code ludwig.user-settings.web.backfill-enabled=true} <em>and</em> the service is in owner mode.
 * Three conditions for one endpoint, because this is the one endpoint in the module whose blast
 * radius is the whole estate rather than one subject.
 *
 * <h2>The two things this controller adds over the service</h2>
 *
 * <p><b>The administrative authority is required outright.</b> Not the self-or-admin check the
 * subject-scoped endpoints use: a backfill is not about a subject, so there is no "self" case that
 * could apply.
 *
 * <p><b>The tenant is the caller's, and cannot be anything else.</b> The service accepts a null
 * tenant to mean every tenant, which is the right shape for a runbook and the wrong one to expose
 * over HTTP; the request body has no tenant field and this method supplies the caller's, so a
 * cross-tenant backfill is not a request that can be constructed here at all.
 *
 * <p>The call runs to completion before it responds. That is fine with {@code maxRows} set and a poor
 * idea without it on a large estate - which is why the response reports whether the run finished, so
 * an operator can drive it in chunks from a script rather than holding one connection open.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class SettingsBackfillController {

    private final SettingsBackfillService backfill;
    private final SettingsAccessPolicy accessPolicy;
    private final SettingsTenantResolver tenantResolver;

    @PostMapping("${ludwig.user-settings.web.admin-base-path:/admin/settings}/backfill")
    public BackfillResponse backfill(@RequestBody(required = false) BackfillRequest request) {
        SettingsSubject caller = callerSubject();
        accessPolicy.requireAdmin(caller);

        BackfillRequest body = request == null
                ? new BackfillRequest(null, null, null, null, null, null)
                : request;
        SettingsBackfillRequest.SettingsBackfillRequestBuilder builder = SettingsBackfillRequest.builder()
                .tenantId(caller.tenantId())
                .settingKeys(orEmpty(body.settingKeys()))
                .consentKeys(orEmpty(body.consentKeys()));
        if (body.includeSettings() != null) {
            builder.includeSettings(body.includeSettings());
        }
        if (body.includeConsents() != null) {
            builder.includeConsents(body.includeConsents());
        }
        if (body.batchSize() != null) {
            builder.batchSize(body.batchSize());
        }
        if (body.maxRows() != null) {
            builder.maxRows(body.maxRows());
        }

        log.info("Backfill requested by {} for tenant {}", caller.ref().subject(), caller.tenantId());
        SettingsBackfillResult result = backfill.backfill(builder.build());
        return new BackfillResponse(result.settingRows(), result.consentRows(),
                result.batches(), result.complete());
    }

    /** The administrator themselves, which is what fixes the tenant this backfill is confined to. */
    private SettingsSubject callerSubject() {
        LudwigPrincipal principal = SecurityPrincipals.require();
        return SettingsSubjects.require(tenantResolver,
                new PrincipalRef(principal.type(), principal.subject()));
    }

    private static Set<String> orEmpty(Set<String> keys) {
        return keys == null ? Set.of() : keys;
    }
}
