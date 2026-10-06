package ru.ludwigandreas.pat.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * Refuses a token-backed caller on the token management surface, before authorization is evaluated.
 *
 * <p>A {@link HandlerInterceptor} rather than a filter, and rather than a check inside each controller
 * method. The reasons for each rejected alternative:
 *
 * <ul>
 *   <li><b>Not a per-method check.</b> A guard somebody has to remember to call is a guard that will be
 *       missing from the seventh endpoint. There are five here today.</li>
 *   <li><b>Not a servlet filter.</b> A filter would have to be added to the security filter chain, which
 *       this module does not own - {@code security-spring-boot-starter} builds it and backs off entirely
 *       when an application declares its own. A module that reached into it would break the application
 *       that took it over.</li>
 *   <li><b>An interceptor runs early enough.</b> {@code preHandle} runs before the handler is invoked, and
 *       {@code @PreAuthorize} is an AOP proxy <em>around</em> the handler - so this refusal precedes the
 *       endpoint's own authorization, which is what the requirement asks for. That ordering is asserted by
 *       a test rather than assumed, because it is a fact about Spring's dispatch and not about this code.
 *   </li>
 * </ul>
 *
 * <p>Refuses any <b>long-lived</b> credential rather than specifically a personal access token. Written that
 * way so that a second credential kind - a deploy key, a signed webhook secret - is covered by this guard on
 * the day it is added. A check against one kind would silently stop covering this surface when the second
 * arrived, and nobody would notice.
 */
@Slf4j
public class PatCredentialGuard implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Credential credential = SecurityPrincipals.currentCredential().orElse(null);
        if (credential == null) {
            return true;
        }
        // Logged at warn: a token-backed request reaching this surface is either a client that does not
        // know the rule, or the second step of using a leaked credential to mint a better one. Both are
        // worth seeing, and the response deliberately tells the caller very little.
        log.warn("Refused a {} credential on the personal access token management surface: {} {}."
                        + " A credential that could mint a credential would make revoking the original"
                        + " accomplish nothing.",
                credential.kind(), request.getMethod(), request.getRequestURI());
        throw new PatCredentialNotPermittedException(credential.kind().name());
    }
}
