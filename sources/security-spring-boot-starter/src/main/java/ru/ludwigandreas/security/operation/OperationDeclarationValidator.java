package ru.ludwigandreas.security.operation;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Fails startup on a declaration that cannot mean what it says.
 *
 * <ul>
 *   <li>A {@link RequiresOperation} on any bean method that names an operation no module catalogues - otherwise a
 *       permanent {@code 403} that reads as a permissions problem.</li>
 *   <li>A handler whose operation's surface disagrees with its path: an {@code ADMIN} operation outside
 *       {@code /admin/v1/}, which would escape the administrative surface's rules, or a {@code BUSINESS} one under
 *       it.</li>
 *   <li>A catalogued operation whose description resolves in no bundle, which discovery would otherwise show as its
 *       raw id. The default bundle is checked; bundle key sets are already required to match across locales.</li>
 * </ul>
 *
 * <p>A startup check rather than a build rule because the catalog is assembled from beans: which operations exist is
 * a fact about the running context, not about any one module's bytecode.
 */
public class OperationDeclarationValidator implements SmartInitializingSingleton {

    private static final String ADMIN_PREFIX = "/admin/";

    private final ListableBeanFactory beanFactory;
    private final OperationCatalog catalog;
    private final OperationDescriptions descriptions;

    /**
     * Creates the validator.
     *
     * @param beanFactory where the declarations are found
     * @param catalog     the declared operations
     * @param descriptions resolves description keys, or null where web-core is absent
     */
    public OperationDeclarationValidator(ListableBeanFactory beanFactory, OperationCatalog catalog,
                                         OperationDescriptions descriptions) {
        this.beanFactory = beanFactory;
        this.catalog = catalog;
        this.descriptions = descriptions;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        if (descriptions != null) {
            requireDescriptions(problems);
        }
        requireCatalogued(problems);
        requireMatchingSurface(problems);
        if (!problems.isEmpty()) {
            throw new SecurityConfigurationException("Operation declarations that cannot be enforced:\n  - "
                    + String.join("\n  - ", problems));
        }
    }

    private void requireDescriptions(List<String> problems) {
        catalog.all().stream()
                .filter(operation -> !descriptions.describes(operation.descriptionKey()))
                .forEach(operation -> problems.add(operation.id() + " has no description: add "
                        + operation.descriptionKey() + " to the declaring module's message bundle"));
    }

    private void requireCatalogued(List<String> problems) {
        for (String name : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(name, false);
            if (type == null) {
                continue;
            }
            ReflectionUtils.doWithMethods(AopUtils.isAopProxy(type) ? type.getSuperclass() : type, method -> {
                RequiresOperation declared =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequiresOperation.class);
                if (declared != null && !catalog.contains(declared.value())) {
                    problems.add(describe(method) + " declares @RequiresOperation(\"" + declared.value() + "\"), which"
                            + " no module in this service catalogues; use the declaring module's operation constant");
                }
            });
        }
    }

    private void requireMatchingSurface(List<String> problems) {
        Map<String, RequestMappingHandlerMapping> mappings =
                beanFactory.getBeansOfType(RequestMappingHandlerMapping.class);
        for (RequestMappingHandlerMapping mapping : mappings.values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                RequiresOperation declared = entry.getValue().getMethodAnnotation(RequiresOperation.class);
                if (declared == null) {
                    continue;
                }
                catalog.find(declared.value()).ifPresent(operation -> {
                    Set<String> paths = entry.getKey().getPatternValues();
                    boolean admin = !paths.isEmpty() && paths.stream().allMatch(path -> path.startsWith(ADMIN_PREFIX));
                    if (admin != (operation.surface() == Surface.ADMIN)) {
                        problems.add(entry.getValue().getShortLogMessage() + " at " + paths + " declares "
                                + operation.surface() + " operation " + operation.id() + "; administrative operations"
                                + " are served under /admin/v1/ and only there");
                    }
                });
            }
        }
    }

    private static String describe(Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }
}
