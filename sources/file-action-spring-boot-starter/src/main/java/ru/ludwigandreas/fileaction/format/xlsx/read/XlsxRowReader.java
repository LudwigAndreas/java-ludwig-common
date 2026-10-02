package ru.ludwigandreas.fileaction.format.xlsx.read;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.RowReader;
import ru.ludwigandreas.fileaction.format.RowVisitor;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Reads one sheet of an OOXML workbook with bounded memory.
 *
 * <h2>The DOM readers are not used here, and that is the module's core guarantee</h2>
 *
 * <p>{@code new XSSFWorkbook(in)} builds the whole workbook as objects. A four-megabyte file of a hundred
 * thousand rows becomes hundreds of megabytes resident, so the heap a service needs becomes a function of
 * whatever a user chooses to upload - and the failure is an {@code OutOfMemoryError} that takes the pod
 * down, in production, on the day somebody uploads a bigger file than usual. Nothing in a test catches it,
 * because test fixtures are small.
 *
 * <p>So the read goes through {@code XSSFReader}, which hands out one sheet's XML as a stream, and
 * {@code XSSFSheetXMLHandler}, which walks it as SAX. What is resident is the current row, the styles table
 * and the shared-strings table. The first two are small; the third is the one real cost, which is why its
 * size is checked from the ZIP directory by {@link ArchiveInspector} before any parsing starts.
 *
 * <p>{@code PoiConfinementTest} fails the build if {@code XSSFWorkbook}, {@code HSSFWorkbook} or
 * {@code WorkbookFactory} is ever referenced anywhere in this module. It is a module-local rule rather than
 * a platform one because {@code export} legitimately DOM-reads an administrator-supplied template, so a
 * platform-wide ban would be red on a module that is correct - the same reasoning that makes
 * {@code SqlConfinementTest} module-local in the two modules with a SQL carve-out.
 *
 * <h2>Two passes, and why the first one is cheap</h2>
 *
 * <p>The headers have to be known before a single data row is read - a file missing a required column is
 * refused outright, and refusing it after reading a hundred thousand rows is work done for nothing. But SAX
 * has no cursor to rewind, so the header is read by parsing the sheet and abandoning the parse immediately
 * after the first row. That pass touches one row's worth of XML whatever the sheet's size.
 */
public final class XlsxRowReader implements RowReader {

    private final OPCPackage container;
    private final XSSFReader reader;
    private final ReadOnlySharedStringsTable strings;
    private final StylesTable styles;
    private final String sheetName;
    private final List<String> headers;
    private final Map<String, String> resolvedHeaders;
    private final RowBinding<?> binding;
    private final ReadBudget budget;
    private final boolean date1904;

    // SUPPRESS CHECKSTYLE ParameterNumber - every field is read from the container exactly once, in the
    // factory method below, and passing the container instead would mean re-reading them per pass.
    @SuppressWarnings("checkstyle:ParameterNumber")
    private XlsxRowReader(OPCPackage container, XSSFReader reader, ReadOnlySharedStringsTable strings,
                          StylesTable styles, String sheetName, List<String> headers,
                          Map<String, String> resolvedHeaders, RowBinding<?> binding, ReadBudget budget,
                          boolean date1904) {
        this.container = container;
        this.reader = reader;
        this.strings = strings;
        this.styles = styles;
        this.sheetName = sheetName;
        this.headers = List.copyOf(headers);
        this.resolvedHeaders = Map.copyOf(resolvedHeaders);
        this.binding = binding;
        this.budget = budget;
        this.date1904 = date1904;
    }

    /**
     * Opens a reader over a workbook, having first inspected its container and read its header row.
     *
     * @param workbook a local file holding the workbook
     * @param binding  what the rows bind to
     * @param budget   the ceilings
     * @return the reader
     */
    static XlsxRowReader open(Path workbook, RowBinding<?> binding, ReadBudget budget) {
        new ArchiveInspector().inspect(workbook, budget);
        OPCPackage container = null;
        try {
            // READ, and a File rather than a stream. The stream overload reads the whole package into the
            // heap, which is the cost this class exists to avoid; see RowReaderFactory's javadoc.
            container = OPCPackage.open(workbook.toFile(), PackageAccess.READ);
            XSSFReader reader = new XSSFReader(container);
            WorkbookProperties properties;
            try (InputStream workbookData = reader.getWorkbookData()) {
                properties = WorkbookProperties.read(workbookData);
            }
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(container);
            StylesTable styles = reader.getStylesTable();
            String sheetName = resolveSheetName(reader, binding);
            SheetHandler header = SheetHandler.forHeaderPass(binding, budget, properties.date1904(),
                    sheetName);
            parseSheet(reader, styles, strings, sheetName, header);
            if (header.headers().isEmpty()) {
                throw new FileRejectedException(ProblemStatus.INVALID,
                        FileActionProblemCodes.NO_HEADER_ROW, sheetName);
            }
            return new XlsxRowReader(container, reader, strings, styles, sheetName, header.headers(),
                    header.resolvedHeaders(), binding, budget, properties.date1904());
        } catch (IOException | OpenXML4JException | SAXException unreadable) {
            closeQuietly(container);
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.UNREADABLE,
                    unreadable, "xlsx");
        } catch (RuntimeException propagated) {
            closeQuietly(container);
            throw propagated;
        }
    }

    /**
     * Finds the sheet to read.
     *
     * <p>A binding naming no sheet reads the first one, which is what a CSV does and what a user who has
     * one sheet expects. A binding naming a sheet that is not there is a file-level refusal naming it: the
     * alternative - silently reading the first sheet - imports the wrong data from a file that looked fine.
     */
    private static String resolveSheetName(XSSFReader reader, RowBinding<?> binding)
            throws IOException, InvalidFormatException {
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        String first = null;
        while (sheets.hasNext()) {
            try (InputStream ignored = sheets.next()) {
                String name = sheets.getSheetName();
                if (first == null) {
                    first = name;
                }
                if (binding.sheet() != null && binding.sheet().equalsIgnoreCase(name)) {
                    return name;
                }
            }
        }
        if (binding.sheet() != null) {
            throw new FileRejectedException(ProblemStatus.INVALID,
                    FileActionProblemCodes.SHEET_NOT_FOUND, binding.sheet());
        }
        if (first == null) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.EMPTY, "xlsx");
        }
        return first;
    }

    private static void parseSheet(XSSFReader reader, StylesTable styles,
                                   ReadOnlySharedStringsTable strings, String sheetName,
                                   SheetHandler handler)
            throws IOException, OpenXML4JException, SAXException {
        XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
        while (sheets.hasNext()) {
            try (InputStream sheet = sheets.next()) {
                if (!sheetName.equals(sheets.getSheetName())) {
                    continue;
                }
                XMLReader parser = org.apache.poi.util.XMLHelper.newXMLReader();
                parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings,
                        handler, handler.formatter(), false));
                try {
                    parser.parse(new InputSource(sheet));
                } catch (SheetHandler.StopParsing stopped) {
                    // The only way out of a SAX parse before the end of the document. Expected, and the
                    // reason StopParsing exists; see its javadoc.
                    return;
                }
                return;
            } catch (javax.xml.parsers.ParserConfigurationException misconfigured) {
                throw new IOException("the XML reader could not be created", misconfigured);
            }
        }
    }

    @Override
    public String sheet() {
        return sheetName;
    }

    @Override
    public List<String> headers() {
        return headers;
    }

    @Override
    public void forEachRow(RowVisitor visitor) {
        SheetHandler data = SheetHandler.forDataPass(binding, budget, date1904, sheetName,
                resolvedHeaders, visitor);
        try {
            parseSheet(reader, styles, strings, sheetName, data);
        } catch (IOException | OpenXML4JException | SAXException unreadable) {
            throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.UNREADABLE,
                    unreadable, "xlsx");
        }
    }

    /** Whether this workbook counts dates from 1904, which the coercion layer needs. */
    public boolean date1904() {
        return date1904;
    }

    @Override
    public void close() throws IOException {
        container.close();
    }

    private static void closeQuietly(OPCPackage container) {
        if (container == null) {
            return;
        }
        try {
            container.close();
        } catch (IOException ignored) {
            // The open failed; the original failure is the one worth reporting, and a close failure on a
            // package that never opened properly adds nothing.
        }
    }
}
