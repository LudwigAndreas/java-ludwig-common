package ru.ludwigandreas.usersettings.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.usersettings.api.SettingsLookup;
import ru.ludwigandreas.usersettings.consent.ConsentGrant;
import ru.ludwigandreas.usersettings.consent.ConsentService;
import ru.ludwigandreas.usersettings.web.dto.ConsentDecisionRequest;
import ru.ludwigandreas.usersettings.web.dto.ConsentResponse;
import ru.ludwigandreas.usersettings.web.dto.ConsentStateResponse;
import ru.ludwigandreas.usersettings.web.dto.ResetSettingsRequest;
import ru.ludwigandreas.usersettings.web.dto.SettingsResponse;
import ru.ludwigandreas.usersettings.web.dto.UpdateSettingsRequest;
import ru.ludwigandreas.usersettings.write.RawSettingWriter;

/**
 * Self-service settings and consents for the authenticated caller.
 *
 * <p>Registered only when {@code ludwig.user-settings.web.enabled=true}, which is off by default. A
 * service has to be able to own its own API shape - its paths, its DTOs, its versioning - and a
 * starter that mounted endpoints without being asked would be deciding that for it. What this is for
 * is the service that has no opinion and wants working endpoints on day one.
 *
 * <p>Every method operates on the caller and takes no subject from the request. There is no path
 * variable to get wrong and no way to address somebody else's settings through this controller at
 * all - which is a stronger guarantee than checking, and is why the administrative endpoints live in
 * a separate controller that a deployment can leave unmounted.
 *
 * <p>In projection mode every write here is refused with a localized 409 saying where the settings
 * are actually managed. The controller is mounted in both modes because it has to answer either way;
 * the beans behind it are the ones that differ.
 */
@RestController
@RequiredArgsConstructor
public class MeSettingsController {

    private final SettingsLookup lookup;
    private final ConsentService consents;
    private final SettingsResponseRenderer mapper;
    private final RawSettingWriter writer;

    @GetMapping("${ludwig.user-settings.web.base-path:/me/settings}")
    public SettingsResponse mySettings() {
        return mapper.toResponse(lookup.getAll(self()), false);
    }

    @PutMapping("${ludwig.user-settings.web.base-path:/me/settings}")
    public SettingsResponse update(@RequestBody UpdateSettingsRequest request) {
        return mapper.toResponse(writer.setAll(self(), request.values()), false);
    }

    /**
     * A POST rather than a DELETE with the keys in the path, because setting keys contain dots and
     * because clearing several at once is one thing the user asked for and should be one transaction.
     */
    @PostMapping("${ludwig.user-settings.web.base-path:/me/settings}/reset")
    public SettingsResponse reset(@RequestBody ResetSettingsRequest request) {
        return mapper.toResponse(writer.resetAll(self(), request.keys()), false);
    }

    @GetMapping("${ludwig.user-settings.web.base-path:/me/settings}/consents")
    public ConsentStateResponse myConsents() {
        return mapper.toResponse(consents.currentState(self()));
    }

    @PostMapping("${ludwig.user-settings.web.base-path:/me/settings}/consents/grant")
    public ConsentResponse grant(@RequestBody ConsentDecisionRequest request, HttpServletRequest http) {
        return mapper.toResponse(consents.grant(self(), evidence(request, http)));
    }

    @PostMapping("${ludwig.user-settings.web.base-path:/me/settings}/consents/revoke")
    public ConsentResponse revoke(@RequestBody ConsentDecisionRequest request, HttpServletRequest http) {
        return mapper.toResponse(consents.revoke(self(), evidence(request, http)));
    }

    /**
     * Evidence is read from the request, never from the body.
     *
     * <p>A client that could state its own address and user agent could state somebody else's, and
     * the whole value of an evidence field is that the subject did not choose it. {@code occurredAt}
     * is this server's clock for the same reason: an interactive decision happened when the request
     * arrived, and a client-supplied timestamp on a consent record is worth nothing.
     */
    private ConsentGrant evidence(ConsentDecisionRequest request, HttpServletRequest http) {
        return ConsentGrant.builder()
                .consentKey(request.consentKey())
                .textVersion(request.textVersion())
                .locale(request.locale())
                .occurredAt(Instant.now())
                .evidenceIp(http.getRemoteAddr())
                .evidenceUserAgent(http.getHeader(HttpHeaders.USER_AGENT))
                .build();
    }

    private PrincipalRef self() {
        LudwigPrincipal principal = SecurityPrincipals.require();
        return new PrincipalRef(principal.type(), principal.subject());
    }
}
