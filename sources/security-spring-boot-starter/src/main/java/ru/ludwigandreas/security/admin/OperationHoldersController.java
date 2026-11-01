package ru.ludwigandreas.security.admin;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.data.ScopeGrant;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.OperationGrants;
import ru.ludwigandreas.security.operation.RequiresOperation;

/**
 * Answers "who may do this?" from the same table enforcement reads - the question an incident asks, as the role
 * blocks of {@code ludwig.security.roles} answer the one an access review asks.
 */
@RestController
public class OperationHoldersController {

    /** The endpoint's path. */
    public static final String PATH = "/admin/v1/security/operations/{id}/holders";

    private final OperationAuthority authority;

    /**
     * Creates the controller.
     *
     * @param authority the role table
     */
    public OperationHoldersController(OperationAuthority authority) {
        this.authority = authority;
    }

    /**
     * The roles holding an operation.
     *
     * @param id the operation
     * @return each holding role with its scope, and the role it came from when inherited
     */
    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresOperation(SecurityAdminOperations.OPERATION_READ)
    public List<HolderView> holders(@PathVariable String id) {
        if (!authority.grants().catalog().contains(id)) {
            throw new AdminObjectNotFoundException("ludwig.security.admin.operation-not-found", id);
        }
        return authority.grants().holders(id).stream().map(HolderView::of).toList();
    }

    /**
     * One role's hold on the operation.
     *
     * @param role  the role
     * @param scope {@code ALL}, or the dimensions joined by {@code +}
     * @param via   the role it was declared on, when inherited through {@code includes}, else null
     */
    public record HolderView(String role, String scope, String via) {

        static HolderView of(OperationGrants.Holder holder) {
            return new HolderView(holder.role(), scopeOf(holder.grant()), holder.via());
        }

        private static String scopeOf(ScopeGrant grant) {
            if (grant.kind() == ScopeGrant.Kind.ALL) {
                return "ALL";
            }
            return grant.dimensions().stream()
                    .map(dimension -> ScopeDimension.OWNER.equals(dimension) ? "OWN"
                            : dimension.name().toUpperCase(java.util.Locale.ROOT))
                    .reduce((left, right) -> left + "+" + right).orElse("");
        }
    }
}
