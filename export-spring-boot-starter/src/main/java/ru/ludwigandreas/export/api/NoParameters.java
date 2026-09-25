package ru.ludwigandreas.export.api;

/**
 * The parameter object for a report that takes none.
 *
 * <p>A type rather than {@code null}, so that the engine, the registry and every source have one
 * shape to handle instead of two. A definition that declared {@code null} parameters would make
 * every {@code SourceContext.parameters()} call a potential null dereference in code that is
 * otherwise free of them.
 */
public record NoParameters() implements ReportParameters {

    /** The only instance there is ever any reason to have. */
    public static final NoParameters INSTANCE = new NoParameters();
}
