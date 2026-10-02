package ru.ludwigandreas.fileaction.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The DOM workbook readers do not appear in this module, and the streaming writer appears in one package.
 *
 * <h2>Why this test is the module's core guarantee</h2>
 *
 * <p>{@code new XSSFWorkbook(in)} builds a whole workbook as objects. A four-megabyte file of a hundred
 * thousand rows becomes hundreds of megabytes resident, so the heap a service needs becomes a function of
 * whatever a user chooses to upload, and the failure is an {@code OutOfMemoryError} that takes the pod down -
 * in production, on the day somebody uploads a bigger file than usual, and never in a test, because test
 * fixtures are small. {@code HSSFWorkbook} is worse: the legacy format has no streaming reader at all, which
 * is why {@code .xls} is refused rather than supported.
 *
 * <p>Reading through {@code XSSFReader}'s SAX path fixes the state of the code. Only a rule stops the next
 * person who needs "just the cell styles" or "just the sheet names" reaching for the convenient API - which
 * is a reasonable local decision every single time, and is how the guarantee is lost.
 *
 * <h2>Why this is module-local and not a platform RuleGroup</h2>
 *
 * <p>{@code export-spring-boot-starter} legitimately calls {@code new XSSFWorkbook(in)}: it reads an
 * administrator-supplied XLSX template, which is a different trust level and a bounded size, and
 * {@code ludwig-bom}'s own note on {@code poi.version} records that this is accepted. A platform-wide ban
 * would be red on a module that is correct.
 *
 * <p>This is the same reasoning that makes {@code SqlConfinementTest} module-local in the two modules with a
 * SQL carve-out: the rule is about this module's contract with its own callers, not about the platform's
 * structure, and {@code architecture-rules} owns the latter.
 */
class PoiConfinementTest {

    private static final String ROOT = "ru.ludwigandreas.fileaction";

    /** The one package permitted to use the streaming writer. */
    private static final String WRITE_PACKAGE = ROOT + ".format.xlsx.write";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("no class in this module references a DOM workbook reader")
    void noDomReaders() {
        noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.apache.poi.xssf.usermodel.XSSFWorkbook")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.apache.poi.hssf.usermodel.HSSFWorkbook")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.apache.poi.ss.usermodel.WorkbookFactory")
                .because("a DOM read makes the heap a function of a user-supplied file; read through"
                        + " XSSFReader's SAX path instead. export's template read is the one legitimate DOM"
                        + " read in the platform and is why this rule is module-local")
                .check(production);
    }

    @Test
    @DisplayName("the streaming writer appears only in the one package that writes files")
    void streamingWriterIsConfined() {
        noClasses()
                .that().resideOutsideOfPackage(WRITE_PACKAGE)
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.apache.poi.xssf.streaming..")
                .because("a second writer elsewhere would be the place somebody reaches for the DOM one;"
                        + " everything this module writes goes through " + WRITE_PACKAGE)
                .check(production);
    }

    @Test
    @DisplayName("no class outside the read package parses a sheet")
    void sheetParsingIsConfined() {
        noClasses()
                .that().resideOutsideOfPackage(ROOT + ".format.xlsx..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.apache.poi.xssf.eventusermodel..")
                .because("the SAX reader and its budget belong together; a second parse elsewhere would not"
                        + " be inspecting the archive first")
                .check(production);
    }
}
