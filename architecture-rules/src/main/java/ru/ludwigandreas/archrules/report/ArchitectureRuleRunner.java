package ru.ludwigandreas.archrules.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.SourceCodeLocation;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.ViolationHandler;

import ru.ludwigandreas.archrules.ArchitectureRuleSuite;
import ru.ludwigandreas.archrules.ResolvedRule;

/**
 * Evaluates a suite once and turns the result into a report.
 *
 * <p>Evaluating everything up front, rather than letting each test evaluate its own rule, is what
 * makes a report possible at all: both the console summary and the JSON file describe the whole run,
 * and they have to be written even when - especially when - rules failed.
 *
 * <p>The other job here is turning ArchUnit's violation <em>sentences</em> into structured records.
 * ArchUnit hands each violation to a {@link ViolationHandler} together with the domain objects it was
 * raised on, which is where the class, the member and the source location come from; the sentence is
 * kept verbatim alongside them, and parsed for a location only when no domain object offered one.
 */
public final class ArchitectureRuleRunner {

    private static final Pattern SOURCE_LOCATION = Pattern.compile("\\(([^()\\s]+\\.(?:java|kt)):(\\d+)\\)");
    private static final Pattern FIRST_TYPE_NAME = Pattern.compile("<([\\p{L}_$][\\p{L}\\p{N}_$.]*)");

    private ArchitectureRuleRunner() {
    }

    /** Runs every rule of the suite and collects the outcome. */
    public static ArchitectureReport run(ArchitectureRuleSuite suite) {
        Objects.requireNonNull(suite, "suite");
        long startedAt = System.nanoTime();
        List<RuleReport> ruleReports = new ArrayList<>();
        for (ResolvedRule rule : suite.rules()) {
            ruleReports.add(evaluate(rule, suite));
        }
        long durationMillis = millisSince(startedAt);
        return new ArchitectureReport(
                ArchitectureReport.SCHEMA_VERSION,
                toolVersion(),
                suite.configuration().serviceName(),
                suite.configuration().basePackages(),
                modulesOf(suite),
                java.time.Instant.now(),
                durationMillis,
                ReportSummary.of(ruleReports),
                ruleReports);
    }

    private static RuleReport evaluate(ResolvedRule rule, ArchitectureRuleSuite suite) {
        long startedAt = System.nanoTime();
        EvaluationResult result = rule.evaluate(suite.classes());
        long durationMillis = millisSince(startedAt);
        List<ViolationDetail> violations = violationsOf(result);
        return new RuleReport(
                rule.id(),
                rule.group(),
                rule.scopeName(),
                rule.description(),
                rule.severity(),
                violations.isEmpty() ? RuleStatus.PASSED : RuleStatus.VIOLATED,
                rule.remediation(),
                violations,
                durationMillis);
    }

    private static List<ViolationDetail> violationsOf(EvaluationResult result) {
        if (!result.hasViolation()) {
            return List.of();
        }
        List<ViolationDetail> details = new ArrayList<>();
        result.handleViolations((ViolationHandler<Object>) (violatingObjects, message) ->
                details.add(detailOf(violatingObjects, message)));
        if (details.isEmpty()) {
            // Slice rules and any future condition whose violating objects this handler does not
            // receive still have their sentence, which is better than dropping the violation.
            for (String detail : result.getFailureReport().getDetails()) {
                details.add(withParsedLocation(detail));
            }
        }
        return List.copyOf(details);
    }

    private static ViolationDetail detailOf(Collection<Object> violatingObjects, String message) {
        for (Object violatingObject : violatingObjects) {
            ViolationDetail detail = fromDomainObject(violatingObject, message);
            if (detail != null) {
                return detail;
            }
        }
        return withParsedLocation(message);
    }

    private static ViolationDetail fromDomainObject(Object violatingObject, String message) {
        if (violatingObject instanceof JavaClass javaClass) {
            return locate(message, javaClass.getName(), null, javaClass.getSourceCodeLocation());
        }
        if (violatingObject instanceof JavaMember member) {
            return locate(message, member.getOwner().getName(), member.getName(), member.getSourceCodeLocation());
        }
        if (violatingObject instanceof Dependency dependency) {
            return locate(message, dependency.getOriginClass().getName(), null,
                    dependency.getSourceCodeLocation());
        }
        if (violatingObject instanceof JavaAccess<?> access) {
            return locate(message, access.getOriginOwner().getName(), access.getOrigin().getName(),
                    access.getSourceCodeLocation());
        }
        return null;
    }

    /**
     * Prefers the domain object's own location, falling back to the line number embedded in the
     * message - some conditions report a location that is more precise than the declaring element's.
     */
    private static ViolationDetail locate(String message, String className, String memberName,
                                          SourceCodeLocation location) {
        String sourceFile = location != null ? location.getSourceFileName() : null;
        int lineNumber = location != null ? location.getLineNumber() : 0;
        if (lineNumber <= 0) {
            Matcher matcher = SOURCE_LOCATION.matcher(message);
            if (matcher.find()) {
                sourceFile = matcher.group(1);
                lineNumber = Integer.parseInt(matcher.group(2));
            }
        }
        return new ViolationDetail(message, className, memberName, sourceFile, lineNumber);
    }

    private static ViolationDetail withParsedLocation(String message) {
        String className = null;
        Matcher typeName = FIRST_TYPE_NAME.matcher(message);
        if (typeName.find()) {
            className = typeName.group(1);
        }
        Matcher location = SOURCE_LOCATION.matcher(message);
        if (location.find()) {
            return new ViolationDetail(message, className, null, location.group(1),
                    Integer.parseInt(location.group(2)));
        }
        return new ViolationDetail(message, className, null, null, 0);
    }

    private static List<String> modulesOf(ArchitectureRuleSuite suite) {
        return suite.configuration().modulePackages().isEmpty()
                ? ru.ludwigandreas.archrules.internal.ModuleDiscovery.discover(suite.classes(),
                suite.configuration().basePackages())
                : suite.configuration().modulePackages();
    }

    private static String toolVersion() {
        String version = ArchitectureRuleRunner.class.getPackage().getImplementationVersion();
        return version != null ? version : "";
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
