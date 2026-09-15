package ru.ludwigandreas.identity.authority;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.identity.entity.SecurityPartnerEntity;
import ru.ludwigandreas.identity.repository.SecurityPartnerRepository;
import ru.ludwigandreas.security.authn.mtls.ClientCertificateDetails;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentity;
import ru.ludwigandreas.security.authn.mtls.PartnerIdentityResolver;
import ru.ludwigandreas.security.config.SecurityProperties;

/**
 * Recognizes a client certificate against the partner registry table, so partners can be onboarded and
 * suspended without a release.
 *
 * <p>Identifiers are tried most-stable first: SPIFFE URI, then DNS SAN, then exact subject DN. When the
 * matched partner pins a certificate hash, the fingerprint must match too - which is what makes it safe
 * to accept a CA that also issues certificates to other organizations.
 *
 * <p>Falls through to the SPIFFE trust-domain prefix for our own workloads, so a peer service is
 * recognized without being enumerated as a partner.
 */
@RequiredArgsConstructor
public class DatabasePartnerIdentityResolver implements PartnerIdentityResolver {

    private final SecurityPartnerRepository partners;
    private final SecurityProperties securityProperties;

    @Override
    @Transactional(readOnly = true)
    public Optional<PartnerIdentity> resolve(ClientCertificateDetails certificate) {
        Optional<SecurityPartnerEntity> matched = bySpiffe(certificate)
                .or(() -> byDns(certificate))
                .or(() -> bySubjectDn(certificate))
                .filter(partner -> fingerprintMatches(partner, certificate));

        if (matched.isPresent()) {
            SecurityPartnerEntity partner = matched.get();
            return Optional.of(PartnerIdentity.partner(partner.getCode(), partner.getDisplayName()));
        }
        return serviceIdentity(certificate);
    }

    private Optional<SecurityPartnerEntity> bySpiffe(ClientCertificateDetails certificate) {
        return certificate.uriSans().stream()
                .map(partners::lookupBySpiffeId)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<SecurityPartnerEntity> byDns(ClientCertificateDetails certificate) {
        return certificate.dnsSans().stream()
                .map(partners::lookupByDnsSan)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<SecurityPartnerEntity> bySubjectDn(ClientCertificateDetails certificate) {
        return certificate.subjectDn() == null
                ? Optional.empty()
                : partners.lookupBySubjectDn(certificate.subjectDn());
    }

    private boolean fingerprintMatches(SecurityPartnerEntity partner, ClientCertificateDetails certificate) {
        String pinned = partner.getCertificateHash();
        if (pinned == null || pinned.isBlank()) {
            return true;
        }
        return certificate.hash() != null && pinned.equalsIgnoreCase(certificate.hash());
    }

    private Optional<PartnerIdentity> serviceIdentity(ClientCertificateDetails certificate) {
        String prefix = securityProperties.getMtls().getServiceIdentityPrefix();
        if (prefix == null || prefix.isBlank()) {
            return Optional.empty();
        }
        return certificate.spiffeId()
                .filter(spiffe -> spiffe.startsWith(prefix))
                .map(PartnerIdentity::service);
    }
}
