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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Renders an authorization failure as a localized RFC 7807 problem.
 *
 * <p>Like the entry point, it says only that access was denied. Which role was missing, which scope
 * excluded the row, which partner the certificate mapped to - all of that is in the audit log, keyed by
 * subject, where support can find it and a caller cannot use it to map out the permission model.
 */
@Slf4j
@RequiredArgsConstructor
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private static final String CODE = "ludwig.security.error.forbidden";

    private final ObjectMapper objectMapper;
    private final SecurityMessages messages;
    private final String problemTypePrefix;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (response.isCommitted()) {
            log.warn("Cannot render a 403 problem: the response is already committed");
            return;
        }

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setType(URI.create(problemTypePrefix + CODE));
        problem.setTitle(messages.get(CODE + ".title", "Forbidden"));
        problem.setDetail(messages.get(CODE, "You are not allowed to perform this operation."));
        problem.setProperty("code", CODE);
        problem.setInstance(URI.create(request.getRequestURI()));

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
