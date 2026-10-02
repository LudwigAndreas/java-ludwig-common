package ru.ludwigandreas.fileaction.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Nothing in this module reads a user's file into the heap in one go.
 *
 * <p>The same rule {@code file-ingest} states on {@code RecordParser}, enforced the same way, for the same
 * reason: a module designed around a bounded heap becomes one that needs a heap the size of the file the
 * moment somebody calls {@code readAllBytes} on the convenient stream, and the failure arrives in production
 * on the day a user uploads something bigger than the fixtures.
 *
 * <p>What this cannot see is a call inside a {@code FileScanner} or a handler a consuming service writes.
 * That is stated at those seams, in their javadoc, and listed in {@code docs/harness-enforcement.md} as
 * deliberately unenforced - because reporting the gap is more useful than pretending the rule is total.
 */
class NoMaterialisationTest {

    private static final String ROOT = "ru.ludwigandreas.fileaction";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    @DisplayName("nothing calls readAllBytes")
    void noReadAllBytes() {
        noClasses()
                .should().callMethodWhere(
                        com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                                com.tngtech.archunit.core.domain.properties.HasName.Predicates
                                        .name("readAllBytes")))
                .because("a user's file must not be held in the heap; stream it instead")
                .check(production);
    }

    @Test
    @DisplayName("nothing reads a whole file through Files' slurping methods")
    void noFilesSlurping() {
        noClasses()
                .should().callMethod(java.nio.file.Files.class, "readAllBytes", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "readAllLines", java.nio.file.Path.class)
                .orShould().callMethod(java.nio.file.Files.class, "readString", java.nio.file.Path.class)
                .because("the submitted file is bounded by a size ceiling, not by the heap; every read of it"
                        + " goes through a reader that streams")
                .check(production);
    }

    @Test
    @DisplayName("nothing uses commons-io's IOUtils to collect a stream")
    void noIoUtilsCollecting() {
        noClasses()
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.apache.commons.io.IOUtils")
                .because("IOUtils.toByteArray and toString are the convenient form of the mistake this"
                        + " module exists to avoid, and the class offers nothing else this module needs")
                .check(production);
    }

    @Test
    @DisplayName("nothing calls MultipartFile.getBytes")
    void noMultipartGetBytes() {
        noClasses()
                .should().callMethod(org.springframework.web.multipart.MultipartFile.class, "getBytes")
                .because("Spring has already spooled the multipart to disk; getBytes copies it into the heap"
                        + " as well, which is the whole file, in memory, for no gain")
                .check(production);
    }
}
