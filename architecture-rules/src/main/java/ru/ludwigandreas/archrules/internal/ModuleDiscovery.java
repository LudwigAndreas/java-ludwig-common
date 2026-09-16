package ru.ludwigandreas.archrules.internal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;

/**
 * Finds the module/bounded-context packages of a service: the direct sub-packages of a base package
 * that actually contain code.
 *
 * <p>Discovery rather than configuration is the default because the module list changes whenever a
 * feature is added, and a cycle rule that silently stops covering a new module would be worse than
 * useless. A service that slices differently declares its modules explicitly instead - see
 * {@link ru.ludwigandreas.archrules.ArchitectureRulesConfiguration.Builder#modules(String...)}.
 */
public final class ModuleDiscovery {

    private ModuleDiscovery() {
    }

    /** Direct sub-packages of the base packages that contain at least one imported class. */
    public static List<String> discover(JavaClasses classes, Collection<String> basePackages) {
        Objects.requireNonNull(classes, "classes");
        Objects.requireNonNull(basePackages, "basePackages");
        Collection<String> modules = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            String packageName = javaClass.getPackageName();
            for (String basePackage : basePackages) {
                if (!packageName.startsWith(basePackage + ".")) {
                    continue;
                }
                String remainder = packageName.substring(basePackage.length() + 1);
                int nextSeparator = remainder.indexOf('.');
                String firstSegment = nextSeparator < 0 ? remainder : remainder.substring(0, nextSeparator);
                if (!firstSegment.isEmpty()) {
                    modules.add(basePackage + "." + firstSegment);
                }
            }
        }
        return List.copyOf(modules);
    }
}
