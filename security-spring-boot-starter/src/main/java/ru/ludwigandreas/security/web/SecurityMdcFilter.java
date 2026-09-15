package ru.ludwigandreas.security.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * Puts the caller's subject, principal type and request id into the logging MDC for the duration of
 * the request.
 *
 * <p>Runs after authentication, so the principal exists; clears the keys in a {@code finally} block,
 * because the threads serving requests are pooled and a leaked MDC entry attributes one caller's log
 * lines to another - a subtle way for an investigation to reach the wrong conclusion.
 *
 * <p>Only the subject goes in, never a name or an email: application logs are shipped widely and the
 * subject is enough to join against the audit trail when someone is entitled to do so.
 */
public class SecurityMdcFilter extends OncePerRequestFilter {

    private static final String MDC_SUBJECT = "principal.subject";
    private static final String MDC_TYPE = "principal.type";
    private static final String MDC_REQUEST_ID = "request.id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(SecurityHeaders.REQUEST_ID);
        try {
            SecurityPrincipals.current().ifPresent(principal -> {
                MDC.put(MDC_SUBJECT, principal.subject());
                MDC.put(MDC_TYPE, principal.type().name());
            });
            if (requestId != null && !requestId.isBlank()) {
                MDC.put(MDC_REQUEST_ID, requestId);
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_SUBJECT);
            MDC.remove(MDC_TYPE);
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
