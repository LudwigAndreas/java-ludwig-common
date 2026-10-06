package ru.ludwigandreas.pat.token;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The published detection pattern for the token wire format, for scanners and for this module's own tests.
 *
 * <p>The pattern is data in {@code pat-secret-detection.properties} rather than a constant here, because its
 * consumers are mostly not Java: a {@code gitleaks} configuration, a pre-receive hook, the SCM's own secret
 * scanning, an operator with {@code ripgrep}. A constant in a class those cannot read would mean the pattern
 * is written twice and the two copies drift - which, for a scanner rule, means the scanner silently matches
 * nothing while continuing to run.
 *
 * <p>This class exists so the Java side reads the same file everyone else does, and so a test can assert that
 * the published pattern actually matches a token this module mints. That assertion is the only thing standing
 * between a published regex and a published regex that is wrong.
 */
public final class PatDetection {

    private static final String RESOURCE = "/ru/ludwigandreas/pat/pat-secret-detection.properties";

    private static final String REGEX_KEY = "pat.detection.regex";

    private static final String PREFIX_KEY = "pat.detection.prefix";

    private PatDetection() {
    }

    /** The compiled detection pattern, as published. */
    public static Pattern pattern() {
        return Pattern.compile(property(REGEX_KEY));
    }

    /** The scannable prefix, as published. */
    public static String prefix() {
        return property(PREFIX_KEY);
    }

    private static String property(String key) {
        Properties properties = new Properties();
        try (InputStream stream = PatDetection.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("the published detection pattern is missing from the jar at "
                        + RESOURCE + " - a scanner configured from it would match nothing");
            }
            properties.load(stream);
        } catch (IOException cause) {
            throw new IllegalStateException("could not read " + RESOURCE, cause);
        }
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(RESOURCE + " declares no " + key);
        }
        return value;
    }
}
