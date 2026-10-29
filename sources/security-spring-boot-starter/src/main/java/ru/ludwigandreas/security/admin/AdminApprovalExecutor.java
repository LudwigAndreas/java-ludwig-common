package ru.ludwigandreas.security.admin;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Runs an approved request through the application's own handlers, interceptors and exception handling.
 *
 * <p>A second {@link DispatcherServlet} over the same application context rather than a servlet forward: a forward
 * commits and closes the approver's response in a real container and runs nothing under MockMvc, while this behaves
 * the same in both. Servlet filters are not run again - the caller is already authenticated on this thread, and the
 * administrative surface guard, an interceptor, checks the approved call exactly as it checks any other.
 */
@Slf4j
final class AdminApprovalExecutor {

    private static final String SERVLET_NAME = "ludwigAdminApprovalDispatcher";

    private final WebApplicationContext context;
    private volatile DispatcherServlet dispatcher;

    AdminApprovalExecutor(WebApplicationContext context) {
        this.context = context;
    }

    AdminApproval.Outcome execute(AdminApproval approval, HttpServletRequest approverRequest,
                                  HttpServletResponse approverResponse) {
        CapturedResponse response = new CapturedResponse(approverResponse);
        try {
            dispatcher(approverRequest.getServletContext())
                    .service(new ApprovedRequest(approverRequest, approval), response);
            return response.outcome();
        } catch (ServletException | IOException | RuntimeException e) {
            // Unresolved by the application's exception handling: recorded as a server error, never retried.
            log.warn("Approved administrative request {} {} failed: {}", approval.request().method(),
                    approval.request().path(), e.toString());
            return new AdminApproval.Outcome(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null, null);
        }
    }

    private DispatcherServlet dispatcher(ServletContext servletContext) throws ServletException {
        DispatcherServlet existing = dispatcher;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (dispatcher == null) {
                DispatcherServlet created = new DispatcherServlet(context);
                created.setPublishContext(false);
                created.setPublishEvents(false);
                created.init(new ServletConfig() {
                    @Override
                    public String getServletName() {
                        return SERVLET_NAME;
                    }

                    @Override
                    public ServletContext getServletContext() {
                        return servletContext;
                    }

                    @Override
                    public String getInitParameter(String name) {
                        return null;
                    }

                    @Override
                    public Enumeration<String> getInitParameterNames() {
                        return Collections.emptyEnumeration();
                    }
                });
                dispatcher = created;
            }
            return dispatcher;
        }
    }
}
