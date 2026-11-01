package ru.ludwigandreas.security.operation;

import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.webcore.problem.ProblemMessages;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * Tells a caller - typically a UI deciding which controls to show - which operations it holds, on every surface, and
 * with which scope.
 *
 * <p>Lists only what the caller holds. Listing the rest would map the permission model for anyone who can
 * authenticate, and a UI has no use for controls it would disable.
 */
@RestController
public class OperationsController {

    /** The discovery endpoint's path. */
    public static final String PATH = "/me/operations";

    private final OperationAuthority authority;
    private final ProblemMessages messages;

    /**
     * Creates the controller.
     *
     * @param authority who holds what
     * @param messages  the bundles the descriptions live in
     */
    public OperationsController(OperationAuthority authority, ProblemMessages messages) {
        this.authority = authority;
        this.messages = messages;
    }

    /**
     * The operations the caller holds.
     *
     * @return each held operation with its scope and localized description, ordered by id
     */
    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresAuthentication
    public List<HeldOperation> operations() {
        Locale locale = UserPreferences.current().locale();
        return authority.heldBy(SecurityPrincipals.require()).entrySet().stream()
                .map(held -> HeldOperation.of(held.getKey(), held.getValue(),
                        messages.get(held.getKey().descriptionKey(), null, held.getKey().id(), locale)))
                .toList();
    }

    /**
     * One operation the caller holds.
     *
     * @param id          the operation id
     * @param sensitivity what it can do
     * @param surface     where it is served
     * @param scope       {@code ALL}, or the dimensions the caller's rows are confined by
     * @param description what it permits, in the caller's locale
     */
    public record HeldOperation(String id, Sensitivity sensitivity, Surface surface, List<String> scope,
                                String description) {

        static HeldOperation of(Operation operation, DataScope scope, String description) {
            return new HeldOperation(operation.id(), operation.sensitivity(), operation.surface(), scopeOf(scope),
                    description);
        }

        /** A dimension as {@code ludwig.security.roles} spells it: {@code OWN} for the owner, else its name. */
        private static String token(ScopeDimension dimension) {
            return ScopeDimension.OWNER.equals(dimension) ? "OWN" : dimension.name().toUpperCase(Locale.ROOT);
        }

        private static List<String> scopeOf(DataScope scope) {
            if (scope.isUnrestricted()) {
                return List.of("ALL");
            }
            return scope.alternatives().stream()
                    .map(alternative -> alternative.values().keySet().stream()
                            .map(HeldOperation::token)
                            .sorted()
                            .reduce((left, right) -> left + "+" + right)
                            .orElse(""))
                    .distinct()
                    .sorted()
                    .toList();
        }
    }
}
