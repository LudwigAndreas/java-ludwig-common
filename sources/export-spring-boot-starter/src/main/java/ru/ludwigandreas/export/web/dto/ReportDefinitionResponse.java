package ru.ludwigandreas.export.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.i18n.ExportMessages;

/**
 * What this service can produce, as one caller may see it.
 *
 * <p>Built per caller, not cached. The column list is the one <em>their</em> authorities allow, so
 * two people asking get two different answers - which is the whole point: a UI that offered a column
 * the user cannot export would produce a 403 at the moment they pressed the button, and a UI that
 * offered all of them would tell everyone which columns exist.
 *
 * @param key            the definition key, which a run request names
 * @param title          the report's title, resolved into the caller's locale
 * @param columns        the columns this caller may export
 * @param formats        the formats they may ask for
 * @param defaultFormat  the one they get if they ask for none
 * @param sortableColumns the columns the source can order by
 * @param filterable     whether this report accepts a {@code $filter} at all
 */
@Schema(description = "A report this service can produce")
public record ReportDefinitionResponse(
        String key,
        String title,
        List<ColumnResponse> columns,
        List<String> formats,
        String defaultFormat,
        List<String> sortableColumns,
        boolean filterable) {

    /**
     * One column a caller may export.
     *
     * @param id     the column id, which a request names
     * @param header the header text, resolved into the caller's locale
     * @param type   the cell shape, so a UI can right-align a number without guessing
     * @param pii    whether it is personal data, so a UI can warn before it is included
     */
    @Schema(description = "A column of a report")
    public record ColumnResponse(String id, String header, String type, boolean pii) {
    }

    /** Projects a definition for one caller. */
    public static ReportDefinitionResponse of(ReportDefinition<?, ?> definition,
                                              Set<String> authorities, ExportMessages messages,
                                              Locale locale) {
        List<ColumnResponse> columns = definition.visibleColumns(authorities).stream()
                .map(column -> new ColumnResponse(column.getId(),
                        messages.resolve(column.getHeaderKey(), locale),
                        column.getFormat().kind().name(), column.isPii()))
                .toList();
        return new ReportDefinitionResponse(definition.getKey(),
                messages.resolve(definition.getTitleKey(), locale), columns,
                definition.getAllowedFormats().stream().map(ReportFormat::id).sorted().toList(),
                definition.getDefaultFormat().id(),
                definition.getSource().sortableColumns().stream().sorted().toList(),
                definition.getFilterEntityType() != null);
    }

    /** Whether this caller may see anything of this definition at all. */
    public static boolean isVisibleTo(ReportDefinition<?, ?> definition, Set<String> authorities) {
        return definition.isRunnableBy(authorities)
                && !definition.visibleColumns(authorities).isEmpty();
    }

    /** The column ids, for a caller assembling a request. */
    public List<String> columnIds() {
        return columns.stream().map(ColumnResponse::id).toList();
    }
}
