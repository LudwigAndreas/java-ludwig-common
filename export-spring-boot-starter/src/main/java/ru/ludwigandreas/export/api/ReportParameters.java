package ru.ludwigandreas.export.api;

/**
 * Marker for a definition's parameter object.
 *
 * <p>A report's parameters are a typed object carrying Bean Validation constraints, not a
 * {@code Map<String, String>} the source pulls values out of. The map is what arrives over HTTP; it
 * stops being a map at the edge, where it is bound and validated, so that a source reads
 * {@code params.from()} as a {@code LocalDate} and a missing or malformed value has already become
 * a 400 with per-field violations in the caller's language.
 *
 * <pre>{@code
 * public record OrderReportParameters(
 *         @NotNull LocalDate from,
 *         @NotNull LocalDate to,
 *         @Size(max = 50) List<String> statuses) implements ReportParameters {
 * }
 * }</pre>
 *
 * <p>The interface is empty because there is nothing every parameter object has in common except
 * being one. It exists so that {@link ReportDefinition} can require a parameter type rather than
 * accepting {@code Object}, which is what keeps a definition from being declared against a type
 * nothing validates.
 *
 * <p>Violation messages are message keys resolved against the caller's locale through
 * {@code web-core}'s bundles, like every other user-facing string here; a constraint carrying a
 * literal English sentence is a Checkstyle failure, not a style opinion.
 */
public interface ReportParameters {
}
