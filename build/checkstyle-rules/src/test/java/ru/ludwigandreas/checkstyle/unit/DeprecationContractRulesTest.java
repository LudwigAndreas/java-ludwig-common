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
import org.junit.jupiter.api.Test;

/**
 * Executes the shared Checkstyle configuration against fixture sources and asserts what it reports.
 *
 * <p>The rules in {@code checkstyle.xml} are the only checks in this repository that are
 * configuration rather than code, so this is the only place they can be tested at all. Two of them
 * carry the deprecation contract - {@code DeprecationWithoutSince} (both annotation members are
 * mandatory) and {@code MissingDeprecated} (the annotation and the Javadoc tag appear together) -
 * and both are regression-prone: {@code DeprecationWithoutSince} is a regular expression with two
 * lookaheads, and a lookahead that is subtly wrong reports nothing rather than reporting something
 * wrong.
 *
 * <p>The fixtures deliberately violate the configuration, so they live under
 * {@code src/test/resources} rather than {@code src/test/java}: the build's own Checkstyle
 * execution scans source directories only, and a fixture in one would fail the build it exists to
 * test.
 */
class DeprecationContractRulesTest {

    /**
     * Substring of the DeprecationWithoutSince message, which the configuration spells out.
     *
     * <p>Written without the leading at-sign on purpose. RegexpSinglelineJava matches the file's
     * text and has no notion of a string literal, so spelling the annotation out here would make
     * this very file violate the rule it is testing.
     */
    private static final String MISSING_MEMBERS = "must declare both `since`";

    /** Checkstyle's own key for MissingDeprecated, stable across releases of the engine. */
    private static final String MISSING_TAG_CHECK = "MissingDeprecatedCheck";

    @Test
    void reportsNothingWhenBothMembersAndTheJavadocTagArePresent() throws Exception {
        List<AuditEvent> events = analyse("CompliantDeprecation.java");

        assertThat(messagesContaining(events, MISSING_MEMBERS)).isEmpty();
        assertThat(sourcesContaining(events, MISSING_TAG_CHECK)).isEmpty();
    }

    @Test
    void reportsBareDeprecatedForBothItsMissingMembersAndItsMissingJavadocTag() throws Exception {
        List<AuditEvent> events = analyse("BareDeprecation.java");

        assertThat(messagesContaining(events, MISSING_MEMBERS)).hasSize(1);
        assertThat(sourcesContaining(events, MISSING_TAG_CHECK)).hasSize(1);
    }

    @Test
    void reportsSinceWithoutForRemoval() throws Exception {
        List<AuditEvent> events = analyse("SinceWithoutForRemoval.java");

        assertThat(messagesContaining(events, MISSING_MEMBERS)).hasSize(1);
        // The Javadoc tag is present on this fixture, so only the members rule may fire.
        assertThat(sourcesContaining(events, MISSING_TAG_CHECK)).isEmpty();
    }

    @Test
    void reportsEveryViolationAsAnError() throws Exception {
        List<AuditEvent> events = analyse("BareDeprecation.java");

        assertThat(messagesContaining(events, MISSING_MEMBERS))
                .allMatch(event -> event.getSeverityLevel() == SeverityLevel.ERROR);
    }

    private static List<AuditEvent> messagesContaining(List<AuditEvent> events, String fragment) {
        return events.stream().filter(event -> event.getMessage().contains(fragment)).toList();
    }

    private static List<AuditEvent> sourcesContaining(List<AuditEvent> events, String fragment) {
        return events.stream().filter(event -> event.getSourceName().contains(fragment)).toList();
    }

    /**
     * Runs the real configuration over one fixture and collects everything it reported.
     *
     * <p>Unrelated violations are expected and are filtered by the assertions rather than
     * suppressed here: a fixture is a scrap of Java and will trip line-length or naming rules that
     * have nothing to do with the rule under test, and pruning the configuration down to one module
     * would stop testing the configuration the build actually uses.
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
        return moduleRoot().resolve("src/test/resources/fixtures/deprecation").resolve(name).toFile();
    }

    /**
     * The module directory, resolved from the working directory Surefire runs in.
     *
     * <p>Read from {@code basedir} rather than from the classpath: the fixtures have to be handed
     * to Checkstyle as files on disk, and the copy under {@code target/test-classes} is a copy -
     * asserting against it would let the source of truth drift from what is tested.
     */
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
