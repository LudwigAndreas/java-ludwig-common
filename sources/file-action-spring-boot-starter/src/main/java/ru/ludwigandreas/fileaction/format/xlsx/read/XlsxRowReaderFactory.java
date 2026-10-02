package ru.ludwigandreas.fileaction.format.xlsx.read;

import java.nio.file.Path;
import org.springframework.stereotype.Component;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.RowReaderFactory;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/** Opens {@link XlsxRowReader}s. */
@Component
public class XlsxRowReaderFactory implements RowReaderFactory {

    @Override
    public SourceFormat format() {
        return SourceFormat.XLSX;
    }

    @Override
    public RowReader open(Path content, RowBinding<?> binding, ReadBudget budget) {
        return XlsxRowReader.open(content, binding, budget);
    }
}
