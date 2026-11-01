package ru.ludwigandreas.security.unit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import ru.ludwigandreas.security.config.SecurityProperties;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.OperationCatalog;
import ru.ludwigandreas.security.operation.OperationContributor;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.security.operation.OperationGrants;

/** Builds the operation model from role blocks the way {@code ludwig.security.roles} would, for the unit tests. */
final class TestOperations {

    private TestOperations() {
    }

    /**
     * An authority over these contributors and roles, every grant {@code ALL}.
     *
     * @param contributors the operations
     * @param roles        role to the operations it holds
     * @return the authority
     */
    static OperationAuthority authority(List<OperationContributor> contributors, Map<String, List<String>> roles) {
        Map<String, SecurityProperties.Role> defined = new LinkedHashMap<>();
        roles.forEach((role, operations) -> {
            Map<String, String> scopes = new LinkedHashMap<>();
            operations.forEach(operation -> scopes.put(operation, "ALL"));
            defined.put(role, OperationGrantsTest.role(scopes));
        });
        return new OperationAuthority(OperationGrants.compile(defined, new OperationCatalog(contributors)), () -> null);
    }

    static OperationEvaluator evaluator(OperationAuthority authority, AuthorizationEventPublisher publisher,
                                        boolean allowPersonalAccessTokens) {
        return new OperationEvaluator(authority, publisher, allowPersonalAccessTokens);
    }
}
