package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;

/**
 * A user-submitted file reaches the platform through one module, and nobody writes a second path.
 *
 * <h2>Why this rule is the deliverable</h2>
 *
 * <p>Before {@code file-action-spring-boot-starter} there was not one {@code MultipartFile} in the repository,
 * and the first service to need a file upload would have written one. The parts that get written badly are
 * predictable, and every one of them is a production incident rather than a code-review remark:
 *
 * <ul>
 *   <li><b>No ceiling before the body is read.</b> A declared content length is a claim by the client and a
 *       chunked upload has none, so a limit checked from it is not a limit.</li>
 *   <li><b>{@code getBytes()}.</b> Spring has already spooled the multipart to disk; this copies the whole file
 *       into the heap as well, so the heap a service needs becomes a function of what a user uploads.</li>
 *   <li><b>{@code new XSSFWorkbook(in)}.</b> A DOM read of a four-megabyte crafted workbook is hundreds of
 *       megabytes resident, and the failure is an {@code OutOfMemoryError} that takes the pod down.</li>
 *   <li><b>A 400 carrying three thousand row errors,</b> which no client can render and no person can read.</li>
 * </ul>
 *
 * <p>Each of those is a reasonable local decision taken by somebody who had no reason to know better. The module
 * fixes the state of the code; only a rule stops the next service repeating it.
 *
 * <h2>What this deliberately does not check</h2>
 *
 * <p>Three things, stated here rather than left to be discovered:
 *
 * <ul>
 *   <li>It does not see a <em>reactive</em> upload - a {@code FilePart} or a {@code Flux<DataBuffer>} - because
 *       no module in this platform is reactive and a rule naming types nothing depends on would be noise. When
 *       one is, this is where the predicate goes.</li>
 *   <li>It does not see an upload taken as a raw {@code byte[]} or {@code InputStream} request body. That is
 *       indistinguishable from any other body by type, and a rule that flagged every {@code InputStream}
 *       parameter would be suppressed everywhere within a week.</li>
 *   <li>It does not check anything about <em>how</em> the one sanctioned module handles a file. That is
 *       {@code PoiConfinementTest} and {@code NoMaterialisationTest}, in that module's own suite, because
 *       {@code export} legitimately DOM-reads an administrator-supplied template and a platform-wide ban would
 *       be red on a module that is correct.</li>
 * </ul>
 */
public final class UploadRules implements ArchitectureRuleSet {

    /** No module outside the file-action starter handles a multipart upload itself. */
    public static final RuleId NO_SECOND_UPLOAD_PATH =
            RuleId.of(RuleGroup.UPLOADS, "no-second-upload-path");

    /** The one module permitted to name the type. */
    private static final String FILE_ACTION_PACKAGES = "ru.ludwigandreas.fileaction..";

    /** Spring's multipart abstraction, which is what a hand-written upload endpoint takes. */
    private static final String MULTIPART_FILE = "org.springframework.web.multipart.MultipartFile";

    /** Spring's multipart request, the other way the same thing is reached. */
    private static final String MULTIPART_REQUEST = "org.springframework.web.multipart.MultipartRequest";

    @Override
    public RuleGroup group() {
        return RuleGroup.UPLOADS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(ArchitectureRule.of(NO_SECOND_UPLOAD_PATH, noSecondUploadPath(),
                "Use file-action-spring-boot-starter instead of taking a MultipartFile. It owns the size"
                        + " ceiling enforced while the body is read, the magic-byte format check, the"
                        + " bounded-memory spreadsheet reader, the content-hash idempotency claim and the"
                        + " paged row-reject resource - and a hand-written endpoint reliably gets the first"
                        + " two and the last one wrong. Declare a RowBinding and a handler, and configure the"
                        + " action."));
    }

    private static ArchRule noSecondUploadPath() {
        return ArchRuleDefinition.noClasses()
                .that(ArchitecturePredicates.residingOutsideOf(List.of(FILE_ACTION_PACKAGES)))
                .should().dependOnClassesThat().haveFullyQualifiedName(MULTIPART_FILE)
                .orShould().dependOnClassesThat().haveFullyQualifiedName(MULTIPART_REQUEST)
                .as("No module handles a multipart upload outside file-action-spring-boot-starter");
    }
}
