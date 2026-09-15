package ru.ludwigandreas.security.authn.mtls;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.security.config.SecurityProperties;

/**
 * Configuration-driven partner registry:
 *
 * <pre>{@code
 * ludwig.security.mtls:
 *   partners:
 *     acme:
 *       display-name: ACME GmbH
 *       spiffe-id: spiffe://partners/acme
 *       # or: subject-dn: "CN=acme-partner,O=ACME GmbH,C=DE"
 *       # or: dns-san: partner.acme.example
 *       certificate-hash: 468ed33be74eee6556d90c0149c1309e9ba61d6425303443c0748a02dd8de688
 *   service-identity-prefix: spiffe://mesh/ns/
 * }</pre>
 *
 * <p>Matching is by SPIFFE URI first, then DNS SAN, then exact subject DN - most stable identifier
 * first. When {@code certificate-hash} is also configured the fingerprint must match too, which pins
 * that partner to one specific certificate; use it for partners whose CA also issues certificates to
 * others, and be aware it has to be updated at every renewal.
 *
 * <p>Fine for a handful of partners that change rarely, and it has the useful property of being
 * reviewable in a pull request. Deployments onboarding partners without a release should register a
 * database-backed {@link PartnerIdentityResolver} instead - everything downstream is unchanged.
 */
@Slf4j
@RequiredArgsConstructor
public class PropertiesPartnerIdentityResolver implements PartnerIdentityResolver {

    private final SecurityProperties properties;

    @Override
    public Optional<PartnerIdentity> resolve(ClientCertificateDetails certificate) {
        SecurityProperties.Mtls config = properties.getMtls();

        for (Map.Entry<String, SecurityProperties.Partner> entry : config.getPartners().entrySet()) {
            SecurityProperties.Partner partner = entry.getValue();
            if (!identifierMatches(certificate, partner)) {
                continue;
            }
            if (!fingerprintMatches(certificate, partner)) {
                log.warn("Certificate matched partner '{}' by identifier but its fingerprint differs from "
                        + "the pinned one; rejecting", entry.getKey());
                return Optional.empty();
            }
            String displayName = partner.getDisplayName() == null ? entry.getKey() : partner.getDisplayName();
            return Optional.of(PartnerIdentity.partner(entry.getKey(), displayName));
        }

        // Not a registered partner: it may still be a workload from our own mesh calling
        // service-to-service, which is recognizable by its SPIFFE trust domain.
        String servicePrefix = config.getServiceIdentityPrefix();
        if (servicePrefix != null && !servicePrefix.isBlank()) {
            return certificate.spiffeId()
                    .filter(spiffe -> spiffe.startsWith(servicePrefix))
                    .map(PartnerIdentity::service);
        }
        return Optional.empty();
    }

    private boolean identifierMatches(ClientCertificateDetails certificate, SecurityProperties.Partner partner) {
        if (hasText(partner.getSpiffeId())) {
            return certificate.uriSans().contains(partner.getSpiffeId());
        }
        if (hasText(partner.getDnsSan())) {
            return certificate.dnsSans().stream()
                    .anyMatch(dns -> dns.equalsIgnoreCase(partner.getDnsSan()));
        }
        if (hasText(partner.getSubjectDn())) {
            return partner.getSubjectDn().equalsIgnoreCase(certificate.subjectDn());
        }
        return false;
    }

    private boolean fingerprintMatches(ClientCertificateDetails certificate, SecurityProperties.Partner partner) {
        if (!hasText(partner.getCertificateHash())) {
            return true;
        }
        String presented = certificate.hash() == null ? "" : certificate.hash().toLowerCase(Locale.ROOT);
        return partner.getCertificateHash().toLowerCase(Locale.ROOT).equals(presented);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
