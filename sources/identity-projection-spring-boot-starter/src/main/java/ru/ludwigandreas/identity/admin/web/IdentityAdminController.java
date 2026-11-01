package ru.ludwigandreas.identity.admin.web;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.identity.admin.IdentityAdminOperations;
import ru.ludwigandreas.identity.admin.service.IdentityAdminService;
import ru.ludwigandreas.identity.admin.service.IdentitySourcesService;
import ru.ludwigandreas.identity.admin.service.IdentitySourcesService.IdentitySourceView;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.EffectiveAuthorities;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.GrantRequest;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.GrantView;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.PartnerRequest;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.PartnerView;
import ru.ludwigandreas.identity.entity.PartnerStatus;
import ru.ludwigandreas.security.operation.RequiresOperation;

/**
 * The {@code identity} administrative area: the partner registry, data-scope grants, and effective authorities.
 */
@RestController
@RequestMapping("/admin/v1/identity")
public class IdentityAdminController {

    private final IdentityAdminService service;
    private final IdentitySourcesService sources;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     * @param sources the identity sources listing
     */
    public IdentityAdminController(IdentityAdminService service, IdentitySourcesService sources) {
        this.service = service;
        this.sources = sources;
    }

    /**
     * Every partner.
     *
     * @return the partners
     */
    @GetMapping("/partners")
    @RequiresOperation(IdentityAdminOperations.PARTNER_READ)
    public List<PartnerView> partners() {
        return service.partners();
    }

    /**
     * One partner, with its version as the {@code ETag} a change names in {@code If-Match}.
     *
     * @param code its code
     * @return the partner
     */
    @GetMapping("/partners/{code}")
    @RequiresOperation(IdentityAdminOperations.PARTNER_READ)
    public ResponseEntity<PartnerView> partner(@PathVariable String code) {
        return withVersion(HttpStatus.OK, service.partner(code));
    }

    /**
     * Onboards a partner.
     *
     * @param request the partner
     * @return {@code 201} with the partner
     */
    @PostMapping("/partners")
    @RequiresOperation(IdentityAdminOperations.PARTNER_ONBOARD)
    public ResponseEntity<PartnerView> onboard(@RequestBody PartnerRequest request) {
        return withVersion(HttpStatus.CREATED, service.onboard(request));
    }

    /**
     * Replaces a partner's name, certificate identifiers and roles.
     *
     * @param code    its code
     * @param ifMatch the version that was read
     * @param request the new values
     * @return the partner; {@code 412} when it changed since that version
     */
    @PutMapping("/partners/{code}")
    @RequiresOperation(IdentityAdminOperations.PARTNER_REPLACE)
    public ResponseEntity<PartnerView> replace(@PathVariable String code,
                                               @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch,
                                               @RequestBody PartnerRequest request) {
        return withVersion(HttpStatus.OK, service.replace(code, request, version(ifMatch)));
    }

    /**
     * Suspends a partner.
     *
     * @param code its code
     * @return the partner
     */
    @PostMapping("/partners/{code}/suspend")
    @RequiresOperation(IdentityAdminOperations.PARTNER_SUSPEND)
    public PartnerView suspend(@PathVariable String code) {
        return service.setStatus(code, PartnerStatus.SUSPENDED);
    }

    /**
     * Reactivates a partner.
     *
     * @param code its code
     * @return the partner
     */
    @PostMapping("/partners/{code}/activate")
    @RequiresOperation(IdentityAdminOperations.PARTNER_ACTIVATE)
    public PartnerView activate(@PathVariable String code) {
        return service.setStatus(code, PartnerStatus.ACTIVE);
    }

    /**
     * Data-scope grants.
     *
     * @param subject  the grantee
     * @param resource the resource type
     * @param active   true for grants in force, false for lapsed ones
     * @return the grants
     */
    @GetMapping("/grants")
    @RequiresOperation(IdentityAdminOperations.GRANT_READ)
    public List<GrantView> grants(@RequestParam(required = false) String subject,
                                  @RequestParam(required = false) String resource,
                                  @RequestParam(required = false) Boolean active) {
        return service.grants(subject, resource, active);
    }

    /**
     * Creates a data-scope grant.
     *
     * @param request the grant
     * @return {@code 201} with the grant
     */
    @PostMapping("/grants")
    @RequiresOperation(IdentityAdminOperations.GRANT_CREATE)
    public ResponseEntity<GrantView> grant(@RequestBody GrantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.grant(request));
    }

    /**
     * Revokes a grant, keeping its row.
     *
     * @param id the grant
     * @return the grant, now lapsed
     */
    @PostMapping("/grants/{id}/revoke")
    @RequiresOperation(IdentityAdminOperations.GRANT_REVOKE)
    public GrantView revoke(@PathVariable UUID id) {
        return service.revoke(id);
    }

    /**
     * What a user is entitled to now.
     *
     * @param subject the user
     * @return the effective authorities
     */
    @GetMapping("/subjects/{subject}/authorities")
    @RequiresOperation(IdentityAdminOperations.AUTHORITY_READ)
    public EffectiveAuthorities authorities(@PathVariable String subject) {
        return service.authorities(subject);
    }

    /**
     * Every declared identity source, with its feeds, user counts and the feeds' own state.
     *
     * @return the sources, by name
     */
    @GetMapping("/sources")
    @RequiresOperation(IdentityAdminOperations.SOURCE_READ)
    public List<IdentitySourceView> identitySources() {
        return sources.sources();
    }

    private static ResponseEntity<PartnerView> withVersion(HttpStatus status, PartnerView partner) {
        return ResponseEntity.status(status).eTag(String.valueOf(partner.version())).body(partner);
    }

    /** {@code If-Match} carries the ETag, quoted or not, and possibly weak; the number inside is the version. */
    private static long version(String ifMatch) {
        String digits = ifMatch.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? -1 : Long.parseLong(digits);
    }
}
