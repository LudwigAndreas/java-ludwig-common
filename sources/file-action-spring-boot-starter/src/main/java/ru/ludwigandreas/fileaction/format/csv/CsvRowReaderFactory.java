package ru.ludwigandreas.fileaction.format.csv;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.RowReaderFactory;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Opens {@link CsvRowReader}s.
 *
 * <p>A CSV needs no local file of its own - it is read forwards once - so this opens a stream over the
 * file the engine supplies and hands it to the reader, which closes it.
 */
@Component
public class CsvRowReaderFactory implements RowReaderFactory {

    @Override
    public SourceFormat format() {
        return SourceFormat.CSV;
    }

    @Override
    public RowReader open(Path content, RowBinding<?> binding, ReadBudget budget) {
        InputStream stream;
        try {
            stream = Files.newInputStream(content);
        } catch (IOException unreadable) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.UNREADABLE,
                    unreadable, SourceFormat.CSV.extension());
        }
        return CsvRowReader.open(stream, binding, budget);
    }
}
