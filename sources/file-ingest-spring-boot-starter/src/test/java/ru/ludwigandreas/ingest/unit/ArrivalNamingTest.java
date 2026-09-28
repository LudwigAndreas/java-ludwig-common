package ru.ludwigandreas.ingest.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import ru.ludwigandreas.ingest.engine.IngestNaming;
import ru.ludwigandreas.storage.api.ObjectUri;

/**
 * Every place this module derives one object's location from another's.
 *
 * <p>Four templates - the sentinel, the receipt, the archive prefix, and the sentinel suffix the
 * discovery excludes by - and all four are string manipulation on a location, which is the category
 * of code that is obviously right and quietly wrong. A sentinel resolved into the wrong prefix means
 * a task that never reads anything; an archive location that loses its prefix means the source is
 * copied over itself and then deleted.
 */
class ArrivalNamingTest {

    @Test
    @DisplayName("a sentinel template resolves next to the data object")
    void sentinelSitsBesideItsObject() {
        ObjectUri data = ObjectUri.parse("s3://drop/in/2026/catalogue.csv.gz");

        ObjectUri sentinel = IngestNaming.sentinelFor(data, "{name}.done");

        assertThat(sentinel.value()).isEqualTo("s3://drop/in/2026/catalogue.csv.gz.done");
    }

    @Test
    @DisplayName("a sentinel template containing a slash names a key outright")
    void sentinelCanLiveInItsOwnPrefix() {
        ObjectUri data = ObjectUri.parse("s3://drop/in/catalogue.csv");

        ObjectUri sentinel = IngestNaming.sentinelFor(data, "markers/{name}.done");

        assertThat(sentinel.value()).isEqualTo("s3://drop/markers/catalogue.csv.done");
    }

    @Test
    @DisplayName("a sentinel for an object at the container root stays at the root")
    void sentinelForARootObject() {
        ObjectUri data = ObjectUri.parse("s3://drop/catalogue.csv");

        assertThat(IngestNaming.sentinelFor(data, "{name}.done").value())
                .isEqualTo("s3://drop/catalogue.csv.done");
    }

    @Test
    @DisplayName("discovery excludes sentinels by the suffix the template implies")
    void discoveryDerivesTheSentinelSuffix() {
        // Ingesting data.csv.done as though it were data produces a run that reads a zero-byte
        // object, applies nothing, balances perfectly and completes - and then the identity
        // constraint remembers it, so the mistake is permanent.
        assertThat(IngestNaming.sentinelSuffix("{name}.done")).isEqualTo(".done");
        assertThat(IngestNaming.sentinelSuffix("{name}.ok")).isEqualTo(".ok");
        // A template that does not start with {name} puts sentinels elsewhere, so there is nothing
        // in this prefix to exclude.
        assertThat(IngestNaming.sentinelSuffix("markers/{name}.done")).isNull();
        assertThat(IngestNaming.sentinelSuffix("")).isNull();
        assertThat(IngestNaming.sentinelSuffix(null)).isNull();
    }

    @Test
    @DisplayName("a receipt key template resolves in the source's container")
    void receiptGoesWhereTheTemplateSays() {
        ObjectUri data = ObjectUri.parse("s3://drop/in/catalogue.csv");

        assertThat(IngestNaming.receiptFor(data, "processed/{name}.receipt.json").value())
                .isEqualTo("s3://drop/processed/catalogue.csv.receipt.json");
    }

    @Test
    @DisplayName("archiving keeps only the object's own name under the prefix")
    void archivingFlattensToThePrefix() {
        ObjectUri data = ObjectUri.parse("s3://drop/in/2026/catalogue.csv");

        // Flattening matches what the prefix is for - getting the object out of the drop prefix -
        // and the date is already in the name of any file where it matters.
        assertThat(IngestNaming.archivedFor(data, "processed/").value())
                .isEqualTo("s3://drop/processed/catalogue.csv");
    }

    @Test
    @DisplayName("an archive prefix is normalised however it was written")
    void archivePrefixSlashesDoNotMatter() {
        ObjectUri data = ObjectUri.parse("s3://drop/in/catalogue.csv");

        for (String prefix : new String[] {"processed", "/processed", "processed/", "/processed/"}) {
            assertThat(IngestNaming.archivedFor(data, prefix).value())
                    .isEqualTo("s3://drop/processed/catalogue.csv");
        }
    }
}
