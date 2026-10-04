package ru.ludwigandreas.checkstyle.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.puppycrawl.tools.checkstyle.Checker;
import com.puppycrawl.tools.checkstyle.ConfigurationLoader;
import com.puppycrawl.tools.checkstyle.PropertiesExpander;
import com.puppycrawl.tools.checkstyle.api.AuditEvent;
import com.puppycrawl.tools.checkstyle.api.AuditListener;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import com.puppycrawl.tools.checkstyle.api.Configuration;
import com.puppycrawl.tools.checkstyle.api.SeverityLevel;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code WeakDigestAlgorithm} rule, shown firing and shown staying silent against the real configuration.
 *
 * <p>A regular expression that is subtly wrong reports nothing rather than reporting something wrong, which
 * is the failure mode {@link DeprecationContractRulesTest} already records for the deprecation rules. This
 * one has an alternation, two optional hyphens and a second branch for the constant form, so the silent
 * halves below are doing as much work as the firing ones.
 *
 * <p>The negative for {@code HmacSHA1} is the assertion most likely to catch a careless widening of the
 * pattern. It is not the digest the rule forbids: HMAC's security does not rest on the collision resistance
 * of its hash, HMAC-SHA1 has no practical break, and it is still required by external APIs where refusing it
 * would mean refusing to integrate rather than improving anything.
 */
class WeakDigestAlgorithmRuleTest {

    /**
     * Substring of the rule's message, which the configuration spells out.
     *
     * <p>Deliberately a fragment that does not itself match the rule's pattern. The check matches file text
     * and has no notion of a string literal, so quoting the forbidden call shape here would make this very
     * file violate the rule it is testing - the same trap {@code DeprecationContractRulesTest} documents.
     */
    private static final String MESSAGE = "Do not name a broken digest algorithm";

    @Test
    @DisplayName("a broken algorithm is reported at the call site and through a constant")
    void reportsWeakAlgorithmInBothForms() throws Exception {
        List<AuditEvent> events = messagesContaining(analyse("WeakDigest.java"), MESSAGE);

        // Two, not one: the call-site branch and the constant-declaration branch of the pattern are
        // separate alternations and a mistake in either is invisible if only the other is exercised.
        assertThat(events).hasSize(2);
    }

    @Test
    @DisplayName("SHA-256, a weak name in prose, and HMAC-SHA1 are all left alone")
    void staysSilentOnStrongAndOnLookalikes() throws Exception {
        assertThat(messagesContaining(analyse("StrongDigest.java"), MESSAGE)).isEmpty();
    }

    @Test
    @DisplayName("the violation is an error, so it fails the build rather than warning")
    void reportsViolationsAsErrors() throws Exception {
        assertThat(messagesContaining(analyse("WeakDigest.java"), MESSAGE))
                .isNotEmpty()
                .allMatch(event -> event.getSeverityLevel() == SeverityLevel.ERROR);
    }

    private static List<AuditEvent> messagesContaining(List<AuditEvent> events, String fragment) {
        return events.stream().filter(event -> event.getMessage().contains(fragment)).toList();
    }

    /**
     * Runs the real configuration over one fixture and collects everything it reported.
     *
     * <p>Unrelated violations are expected and are filtered by the assertions rather than pruned from the
     * configuration, for the reason {@link DeprecationContractRulesTest} gives: a fixture is a scrap of Java
     * and will trip naming or line-length rules that have nothing to do with the rule under test, and
     * reducing the configuration to one module would stop testing the configuration the build actually uses.
     */
    private static List<AuditEvent> analyse(String fixture) throws CheckstyleException {
        Configuration configuration = ConfigurationLoader.loadConfiguration(
                configFile().toString(),
                new PropertiesExpander(new Properties()),
                ConfigurationLoader.IgnoredModulesOptions.EXECUTE);

        List<AuditEvent> events = new ArrayList<>();
        Checker checker = new Checker();
        checker.setModuleClassLoader(Checker.class.getClassLoader());
        checker.configure(configuration);
        checker.addListener(collectingListener(events));
        try {
            checker.process(List.of(fixtureFile(fixture)));
        } finally {
            checker.destroy();
        }
        return events;
    }

    private static Path configFile() {
        return moduleRoot().resolve("src/main/resources/ru/ludwigandreas/checkstyle/checkstyle.xml");
    }

    private static File fixtureFile(String name) {
        return moduleRoot().resolve("src/test/resources/fixtures/digest").resolve(name).toFile();
    }

    private static Path moduleRoot() {
        return Paths.get(System.getProperty("basedir", System.getProperty("user.dir")));
    }

    private static AuditListener collectingListener(List<AuditEvent> events) {
        return new AuditListener() {
            @Override
            public void auditStarted(AuditEvent event) {
                // Nothing to collect: the event carries no violation.
            }

            @Override
            public void auditFinished(AuditEvent event) {
                // Nothing to collect: the event carries no violation.
            }

            @Override
            public void fileStarted(AuditEvent event) {
                // Nothing to collect: the event carries no violation.
            }

            @Override
            public void fileFinished(AuditEvent event) {
                // Nothing to collect: the event carries no violation.
            }

            @Override
            public void addError(AuditEvent event) {
                events.add(event);
            }

            @Override
            public void addException(AuditEvent event, Throwable throwable) {
                throw new IllegalStateException("Checkstyle failed on " + event.getFileName(), throwable);
            }
        };
    }
}
