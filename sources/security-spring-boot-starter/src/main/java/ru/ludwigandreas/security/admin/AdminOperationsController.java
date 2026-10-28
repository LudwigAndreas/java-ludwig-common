package ru.ludwigandreas.security.admin;

import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.webcore.preference.UserPreferences;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * Tells a caller - typically an administrative UI deciding which controls to show - which operations it holds.
 *
 * <p>Lists only what the caller holds. Listing the rest would map the permission model for anyone who can
 * authenticate, and a UI has no use for controls it would disable.
 */
@RestController
public class AdminOperationsController {

    /** The discovery endpoint's path. */
    public static final String PATH = "/admin/v1/me/operations";

    private final AdminGrants grants;
    private final ProblemMessages messages;

    /**
     * Creates the controller.
     *
     * @param grants   the validated grants
     * @param messages the module bundles the operation descriptions live in
     */
    public AdminOperationsController(AdminGrants grants, ProblemMessages messages) {
        this.grants = grants;
        this.messages = messages;
    }

    /**
     * The operations the caller holds.
     *
     * @return each held operation with its localized description, ordered by id
     */
    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @AdminAuthenticated
    public List<HeldOperation> operations() {
        Locale locale = UserPreferences.current().locale();
        return grants.heldBy(SecurityPrincipals.require()).stream()
                .map(operation -> new HeldOperation(operation.id(), operation.area(), operation.tier().token(),
                        messages.get(operation.descriptionKey(), null, operation.id(), locale)))
                .toList();
    }

    /**
     * One operation the caller holds.
     *
     * @param id          the operation id
     * @param area        its area
     * @param tier        its tier, as written in the id
     * @param description what it permits, in the caller's locale
     */
    public record HeldOperation(String id, String area, String tier, String description) {
    }
}
