package ru.ludwigandreas.security.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.admin.AdminApprovals.ApprovalView;
import ru.ludwigandreas.security.principal.SecurityPrincipals;

/**
 * Four-eyes approvals: open to any caller the surface admits, with what each sees and decides limited by the
 * operations they hold.
 */
@RestController
@RequestMapping(path = AdminApprovals.PATH, produces = MediaType.APPLICATION_JSON_VALUE)
public class AdminApprovalsController {

    private final AdminApprovals approvals;

    /**
     * Creates the controller.
     *
     * @param approvals the approvals
     */
    public AdminApprovalsController(AdminApprovals approvals) {
        this.approvals = approvals;
    }

    /**
     * Approvals of the operations the caller holds, newest first.
     *
     * @param status only this state
     * @param cursor the previous page's cursor
     * @param size   the page size
     * @return the page
     */
    @GetMapping
    @AdminAuthenticated
    public AdminPage<ApprovalView> list(@RequestParam(required = false) AdminApprovalState status,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false) Integer size) {
        return approvals.list(SecurityPrincipals.require(), status, cursor, size);
    }

    /**
     * One approval.
     *
     * @param id the approval id
     * @return the approval
     */
    @GetMapping("/{id}")
    @AdminAuthenticated
    public ApprovalView get(@PathVariable UUID id) {
        return approvals.get(SecurityPrincipals.require(), id);
    }

    /**
     * Approves and executes a pending call; the caller must hold its operation and not be its requester.
     *
     * @param id       the approval id
     * @param request  this request, which the stored one is replayed over
     * @param response this response
     * @return the approval, with the execution's outcome
     */
    @PostMapping("/{id}/approve")
    @AdminAuthenticated
    public ApprovalView approve(@PathVariable UUID id, HttpServletRequest request, HttpServletResponse response) {
        return approvals.approve(SecurityPrincipals.require(), id, request, response);
    }

    /**
     * Rejects a pending call.
     *
     * @param id the approval id
     * @return the approval
     */
    @PostMapping("/{id}/reject")
    @AdminAuthenticated
    public ApprovalView reject(@PathVariable UUID id) {
        return approvals.reject(SecurityPrincipals.require(), id);
    }

    /**
     * Withdraws one's own pending call.
     *
     * @param id the approval id
     * @return the approval
     */
    @PostMapping("/{id}/withdraw")
    @AdminAuthenticated
    public ApprovalView withdraw(@PathVariable UUID id) {
        return approvals.withdraw(SecurityPrincipals.require(), id);
    }
}
