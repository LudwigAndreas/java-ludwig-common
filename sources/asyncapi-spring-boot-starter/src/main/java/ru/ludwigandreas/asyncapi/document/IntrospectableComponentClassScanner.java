package ru.ludwigandreas.asyncapi.document;

import io.github.springwolf.core.asyncapi.scanners.classes.spring.ComponentClassScanner;
import io.github.springwolf.core.configuration.docket.AsyncApiDocketService;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * Springwolf's component scan, without the classes whose methods cannot be resolved on this classpath.
 *
 * <p>The scan covers every {@code @Component} class under {@code ru.ludwigandreas}, registered as a bean or not, and
 * reflects on each one's methods. A platform starter may ship a controller behind an optional dependency - an
 * administrative controller whose methods return the security starter's types, registered only where that starter
 * is present - and in a service without it, reflecting on that class throws {@link LinkageError} and fails the whole
 * document. Such a class cannot be a bean in that service, so it declares no listener the document could miss.
 */
public class IntrospectableComponentClassScanner extends ComponentClassScanner {

    private static final Logger LOG = LoggerFactory.getLogger(IntrospectableComponentClassScanner.class);

    /**
     * Creates the scanner.
     *
     * @param docket      Springwolf's docket
     * @param environment the environment
     */
    public IntrospectableComponentClassScanner(AsyncApiDocketService docket, Environment environment) {
        super(docket, environment);
    }

    @Override
    public Set<Class<?>> scan() {
        return super.scan().stream().filter(IntrospectableComponentClassScanner::introspectable)
                .collect(Collectors.toSet());
    }

    static boolean introspectable(Class<?> type) {
        try {
            type.getDeclaredMethods();
            return true;
        } catch (LinkageError unresolved) {
            LOG.debug("Not scanning {} for listeners: its methods name a type absent from this classpath ({})",
                    type.getName(), unresolved.getMessage());
            return false;
        }
    }
}
