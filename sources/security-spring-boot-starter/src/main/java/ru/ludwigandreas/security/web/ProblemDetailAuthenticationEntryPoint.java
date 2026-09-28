package ru.ludwigandreas.security.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Renders an unauthenticated request as an RFC 7807 problem in the caller's language, instead of
 * Spring Security's default empty 401 with a {@code WWW-Authenticate} header.
 *
 * <p>Two deliberate choices. The body never quotes the underlying {@link AuthenticationException}:
 * "JWT expired at 12:03" and "no partner registered for this certificate" are useful to an attacker
 * probing the surface and useless to a legitimate client, which only needs to know it must
 * re-authenticate. And {@code WWW-Authenticate: Bearer} is suppressed for browser traffic, because the
 * browser reaction to it - a native credential prompt - is never what a session-based UI wants; the UI
 * reads the 401 and redirects to its own login.
 */
@Slf4j
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String CODE = "ludwig.security.error.unauthorized";

    private final ObjectMapper objectMapper;
    private final SecurityMessages messages;
    private final String problemTypePrefix;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            // Something downstream already started writing. Appending a problem body now would corrupt
            // that response rather than replace it, and the status line is already on the wire.
            log.warn("Cannot render a 401 problem: the response is already committed");
            return;
        }

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setType(URI.create(problemTypePrefix + CODE));
        problem.setTitle(messages.get(CODE + ".title", "Unauthorized"));
        problem.setDetail(messages.get(CODE, "Authentication is required to access this resource."));
        problem.setProperty("code", CODE);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
