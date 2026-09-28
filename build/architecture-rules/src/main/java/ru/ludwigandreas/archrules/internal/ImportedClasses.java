package ru.ludwigandreas.archrules.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import ru.ludwigandreas.archrules.ArchitectureRulesConfiguration;

/**
 * Imports - and caches - the classes under analysis.
 *
 * <p>Scanning a service's bytecode takes seconds, and a suite runs every rule against the same class
 * set, so the import is shared per (packages, import options) key for the lifetime of the JVM. This
 * is the same trade-off ArchUnit's own JUnit engine makes; without it a build with several
 * architecture test classes would re-scan the whole service once per class.
 */
public final class ImportedClasses {

    private static final Map<String, JavaClasses> CACHE = new ConcurrentHashMap<>();

    private ImportedClasses() {
    }

    public static JavaClasses of(ArchitectureRulesConfiguration configuration) {
        return CACHE.computeIfAbsent(cacheKey(configuration), key -> new ClassFileImporter()
                .withImportOptions(configuration.importOptions())
                .importPackages(configuration.basePackages()));
    }

    /** Drops the cache. Only needed by tests that import the same packages with different options. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static String cacheKey(ArchitectureRulesConfiguration configuration) {
        List<String> optionKeys = new ArrayList<>();
        for (ImportOption option : configuration.importOptions()) {
            // Enum constants and singletons identify themselves by name; a lambda or an anonymous
            // class cannot, so it falls back to identity and simply does not share a cache entry.
            optionKeys.add(option instanceof Enum<?> constant
                    ? option.getClass().getName() + '#' + constant.name()
                    : option.getClass().getName() + '@' + System.identityHashCode(option));
        }
        return configuration.basePackages() + "|" + optionKeys;
    }
}
