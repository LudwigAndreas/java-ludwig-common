package ru.ludwigandreas.security.authn.mtls;

import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.Singular;

/**
 * One verified client certificate, as described by the proxy that terminated the TLS handshake.
 *
 * <p>Everything here is a <em>verified</em> fact: Envoy has already checked the chain against the
 * configured trust bundle and only then wrote the header. This type carries no raw certificate and no
 * verification logic, because the service must not be in the business of re-deciding trust - it either
 * trusts the proxy that produced the header or it must not read the header at all
 * ({@link MutualTlsAuthenticationFilter} enforces that).
 *
 * @param by        the proxy's own identity (Envoy's {@code By})
 * @param hash      SHA-256 of the client certificate; the most stable handle for pinning a partner to
 *                  an exact certificate, and what an audit record should quote
 * @param subjectDn the certificate's subject distinguished name
 * @param uriSans   URI subject-alternative names - SPIFFE ids live here ({@code spiffe://...})
 * @param dnsSans   DNS subject-alternative names
 */
@Builder
public record ClientCertificateDetails(
        String by,
        String hash,
        String subjectDn,
        @Singular("uriSan") List<String> uriSans,
        @Singular("dnsSan") List<String> dnsSans) {

    public Optional<String> firstUriSan() {
        return uriSans.stream().findFirst();
    }

    public Optional<String> spiffeId() {
        return uriSans.stream().filter(uri -> uri.startsWith("spiffe://")).findFirst();
    }
}
