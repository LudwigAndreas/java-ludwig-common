package ru.ludwigandreas.security.admin;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * Fails startup when a handler declares an administrative operation no module catalogues.
 *
 * <p>A startup check rather than a build rule because the catalog is assembled from beans: which operations exist
 * is a fact about the running context, not about any one module's bytecode. Without it the same mistake surfaces as
 * a permanent {@code 403} on the endpoint, which reads as a permissions problem.
 *
 * <p>It also fails startup when a catalogued operation's description key resolves in no bundle. Discovery would
 * otherwise show an administrator the raw id ({@code outbox.privileged}) and nothing would notice. The default bundle
 * is checked, which is enough because bundle key sets are already required to match across locales.
 */
public class AdminHandlerValidator implements SmartInitializingSingleton {

    private final ListableBeanFactory beanFactory;
    private final AdminOperationCatalog catalog;
    private final ProblemMessages messages;

    /**
     * Creates the validator.
     *
     * @param beanFactory where the request mappings are found
     * @param catalog     the declared operations
     * @param messages    the bundles the operation descriptions are resolved from
     */
    public AdminHandlerValidator(ListableBeanFactory beanFactory, AdminOperationCatalog catalog,
                                 ProblemMessages messages) {
        this.beanFactory = beanFactory;
        this.catalog = catalog;
        this.messages = messages;
    }

    @Override
    public void afterSingletonsInstantiated() {
        requireDescriptions();
        for (RequestMappingHandlerMapping mapping : beanFactory.getBeansOfType(RequestMappingHandlerMapping.class)
                .values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                AdminAccess access = entry.getValue().getMethodAnnotation(AdminAccess.class);
                if (access != null && !catalog.contains(access.value())) {
                    throw new SecurityConfigurationException("Handler " + entry.getValue().getShortLogMessage()
                            + " declares @AdminAccess(\"" + access.value() + "\"), which no module in this service"
                            + " catalogues. Use the declaring module's operation constant.");
                }
            }
        }
    }

    private void requireDescriptions() {
        // Locale.ROOT is the default bundle: ProblemMessages turns off the fall-back to the server's locale.
        List<String> missing = catalog.all().stream()
                .filter(operation -> messages.resolve(operation.descriptionKey(), null, Locale.ROOT).isEmpty())
                .map(operation -> operation.id() + " (" + operation.descriptionKey() + ")")
                .toList();
        if (!missing.isEmpty()) {
            throw new SecurityConfigurationException("Administrative operations without a description in any bundle: "
                    + String.join(", ", missing) + ". Add each key to the declaring module's message bundle.");
        }
    }
}
