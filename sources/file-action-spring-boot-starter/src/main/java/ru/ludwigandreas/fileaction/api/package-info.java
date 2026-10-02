/**
 * What the author of a file action writes against, and nothing else.
 *
 * <p>Three things come out of this package and no fourth: a {@link ru.ludwigandreas.fileaction.api.RowBinding}
 * declaring how a sheet's columns become a typed record, one of the two shapes of
 * {@link ru.ludwigandreas.fileaction.api.FileActionHandler} applying those records, and the
 * {@link ru.ludwigandreas.fileaction.api.FileAction} annotation naming the configuration block the
 * pair belongs to. Everything the module does around them - admission, the object store, the
 * lifecycle, the commit policy, the reject artifacts, the HTTP surface - is configuration.
 *
 * <p>The split is the one {@code file-ingest-spring-boot-starter} and
 * {@code reconciliation-spring-boot-starter} use, for the same reason: the size budget, the execution
 * mode, the commit policy, the confirm TTL and the report format are identical in shape across every
 * file action in every service, so they are YAML; the binding and the handler genuinely differ, so
 * they are Java.
 */
package ru.ludwigandreas.fileaction.api;
