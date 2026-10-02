package ru.ludwigandreas.fileaction.unit;

import java.time.Duration;
import ru.ludwigandreas.fileaction.format.ReadBudget;

/** Budgets for tests: a generous one, and a deliberately tiny one per ceiling. */
final class ReadBudgets {

    private ReadBudgets() {
    }

    /** Big enough that no ceiling fires, so a test asserting behaviour is not asserting a limit. */
    static ReadBudget generous() {
        return new ReadBudget(25L * 1024 * 1024, 100_000, 1_000, 0.001d,
                512L * 1024 * 1024, 16L * 1024 * 1024, 32_000, Duration.ofMinutes(2));
    }

    /** The generous budget with a row ceiling of {@code maxRows}. */
    static ReadBudget withMaxRows(int maxRows) {
        ReadBudget base = generous();
        return new ReadBudget(base.maxBytes(), maxRows, base.maxArchiveEntries(),
                base.minInflateRatio(), base.maxUncompressedBytes(), base.maxSharedStringBytes(),
                base.maxCellCharacters(), base.readTimeout());
    }

    /** The generous budget with a cell-length ceiling. */
    static ReadBudget withMaxCellCharacters(int characters) {
        ReadBudget base = generous();
        return new ReadBudget(base.maxBytes(), base.maxRows(), base.maxArchiveEntries(),
                base.minInflateRatio(), base.maxUncompressedBytes(), base.maxSharedStringBytes(),
                characters, base.readTimeout());
    }

    /** The generous budget with an archive entry-count ceiling. */
    static ReadBudget withMaxArchiveEntries(int entries) {
        ReadBudget base = generous();
        return new ReadBudget(base.maxBytes(), base.maxRows(), entries, base.minInflateRatio(),
                base.maxUncompressedBytes(), base.maxSharedStringBytes(), base.maxCellCharacters(),
                base.readTimeout());
    }

    /** The generous budget with a total-inflation ceiling. */
    static ReadBudget withMaxUncompressedBytes(long bytes) {
        ReadBudget base = generous();
        return new ReadBudget(base.maxBytes(), base.maxRows(), base.maxArchiveEntries(),
                base.minInflateRatio(), bytes, base.maxSharedStringBytes(), base.maxCellCharacters(),
                base.readTimeout());
    }

    /** The generous budget with a compression-ratio floor. */
    static ReadBudget withMinInflateRatio(double ratio) {
        ReadBudget base = generous();
        return new ReadBudget(base.maxBytes(), base.maxRows(), base.maxArchiveEntries(), ratio,
                base.maxUncompressedBytes(), base.maxSharedStringBytes(), base.maxCellCharacters(),
                base.readTimeout());
    }

    /** The generous budget with a shared-strings ceiling. */
    static ReadBudget withMaxSharedStringBytes(long bytes) {
        ReadBudget base = generous();
        return new ReadBudget(base.maxBytes(), base.maxRows(), base.maxArchiveEntries(),
                base.minInflateRatio(), base.maxUncompressedBytes(), bytes, base.maxCellCharacters(),
                base.readTimeout());
    }
}
