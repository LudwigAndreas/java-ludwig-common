package ru.ludwigandreas.security.authn.mtls;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Decides whether a request's immediate peer is allowed to speak about client certificates.
 *
 * <p>This is the load-bearing check of the whole mTLS path. {@code x-forwarded-client-cert} is an
 * ordinary HTTP header: anything that can open a TCP connection to the service can send one claiming to
 * be any partner. What makes it trustworthy is not the header, it is the certainty that the only thing
 * able to reach this port is the proxy that wrote it. This class is where that certainty is asserted,
 * and it is why the header is rejected - not ignored - when it arrives from anywhere else.
 *
 * <p>The CIDR list must be as narrow as the deployment allows (the mesh sidecar, the ingress subnet).
 * {@code 0.0.0.0/0} here is equivalent to having no authentication on the partner path at all, and the
 * module refuses to start with it.
 */
public class TrustedProxies {

    private final List<IpAddressMatcher> matchers;

    public TrustedProxies(List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            throw new SecurityConfigurationException(
                    "ludwig.security.mtls.trusted-proxies is empty while mTLS authentication is enabled. "
                            + "Without it any client could forge x-forwarded-client-cert and impersonate a "
                            + "partner. Set it to the CIDR of the proxy/sidecar that terminates client TLS.");
        }
        for (String cidr : cidrs) {
            if ("0.0.0.0/0".equals(cidr.trim()) || "::/0".equals(cidr.trim())) {
                throw new SecurityConfigurationException(
                        "ludwig.security.mtls.trusted-proxies contains " + cidr + ", which trusts every "
                                + "peer and makes partner authentication forgeable. Narrow it to the "
                                + "proxy's actual address range.");
            }
        }
        this.matchers = cidrs.stream().map(String::trim).map(IpAddressMatcher::new).toList();
    }

    /**
     * The decision every caller should use: it resolves the peer address itself, from the unwrapped
     * request, rather than trusting whatever {@code getRemoteAddr()} currently reports.
     */
    public boolean isTrusted(HttpServletRequest request) {
        return isTrustedAddress(peerAddressOf(request));
    }

    /**
     * Address-only form, for callers that already hold a peer address they know to be genuine. Named
     * apart from {@link #isTrusted(HttpServletRequest)} rather than overloading it: overloads that
     * differ only by a reference type are ambiguous at a {@code null} argument, and "which overload did
     * this resolve to" is not a question worth having in the one check that decides whether an identity
     * header may be believed.
     */
    public boolean isTrustedAddress(String remoteAddress) {
        if (remoteAddress == null) {
            return false;
        }
        return matchers.stream().anyMatch(matcher -> matcher.matches(remoteAddress));
    }

    /**
     * The address of the TCP peer that actually opened this connection, taken from the innermost
     * request rather than from whatever wrapper is on top.
     *
     * <p>This is not defensive coding, it closes a concrete hole. With
     * {@code server.forward-headers-strategy=framework}, Spring's {@code ForwardedHeaderFilter} wraps
     * the request and makes {@code getRemoteAddr()} return the address parsed out of
     * {@code X-Forwarded-For} - a header any client can set to anything. A trusted-proxy check reading
     * that value would accept {@code X-Forwarded-For: 10.4.0.1} from an arbitrary caller and then
     * believe their forged {@code x-forwarded-client-cert}: full partner impersonation from an
     * unauthenticated request. Unwrapping to the container's own request reaches the value the kernel
     * reported, which no header can influence.
     *
     * <p>The remaining case unwrapping cannot fix is Tomcat's {@code RemoteIpValve}
     * ({@code forward-headers-strategy=native}), which rewrites the address on the request object
     * itself, before any filter runs. {@code MutualTlsAutoConfiguration} refuses to start in that
     * combination unless it is explicitly acknowledged.
     */
    public static String peerAddressOf(HttpServletRequest request) {
        ServletRequest current = request;
        while (current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        return current.getRemoteAddr();
    }
}
