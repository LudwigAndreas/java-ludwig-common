package ru.ludwigandreas.fileaction.engine;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import ru.ludwigandreas.fileaction.format.RowReaderFactory;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/**
 * The reader factories, by format.
 *
 * <p>A registry rather than an {@code if} on the format, so that the set of readable formats is one list in one
 * place and a format with no factory is a startup failure rather than a {@code null} at read time.
 */
public class RowReaderFactoryRegistry {

    private final Map<SourceFormat, RowReaderFactory> byFormat = new EnumMap<>(SourceFormat.class);

    /**
     * Indexes the available factories.
     *
     * @param factories every factory bean
     */
    public RowReaderFactoryRegistry(Collection<RowReaderFactory> factories) {
        for (RowReaderFactory factory : factories) {
            RowReaderFactory clash = byFormat.put(factory.format(), factory);
            if (clash != null) {
                throw new IllegalStateException("two factories claim " + factory.format() + ": "
                        + clash.getClass().getName() + " and " + factory.getClass().getName());
            }
        }
        for (SourceFormat format : SourceFormat.values()) {
            if (!byFormat.containsKey(format)) {
                throw new IllegalStateException(
                        "no RowReaderFactory for " + format + ". SourceFormat is a closed set and every"
                                + " member of it must be readable, or the enum is claiming something the"
                                + " module cannot do");
            }
        }
    }

    /**
     * The factory for a format.
     *
     * @param format the sniffed format
     * @return the factory
     */
    public RowReaderFactory require(SourceFormat format) {
        RowReaderFactory factory = byFormat.get(format);
        if (factory == null) {
            throw new IllegalStateException("no RowReaderFactory for " + format);
        }
        return factory;
    }
}
