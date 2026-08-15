package ru.ludwigandreas.hotreload.template;

import freemarker.cache.TemplateLoader;
import freemarker.template.Configuration;

import java.io.IOException;
import java.io.Reader;

/**
 * Wraps a delegate FreeMarker {@link TemplateLoader} (typically a {@code FileTemplateLoader}) and adds
 * {@link #invalidateAll()}, called by {@link ru.ludwigandreas.hotreload.core.watch.FileResourceWatcher}
 * whenever the template directory changes. This clears FreeMarker's own template cache immediately
 * rather than waiting for its lazy {@code templateUpdateDelay} check to next fire on request, so a
 * changed template is guaranteed to be picked up on the very next render even under low/no traffic.
 */
public class HotReloadableTemplateLoader implements TemplateLoader {

    private final TemplateLoader delegate;
    private volatile Configuration configuration;

    public HotReloadableTemplateLoader(TemplateLoader delegate) {
        this.delegate = delegate;
    }

    /** Must be called once the owning {@link Configuration} exists, so {@link #invalidateAll()} can clear its cache. */
    public void bindTo(Configuration configuration) {
        this.configuration = configuration;
    }

    public void invalidateAll() {
        Configuration current = configuration;
        if (current != null) {
            current.clearTemplateCache();
        }
    }

    @Override
    public Object findTemplateSource(String name) throws IOException {
        return delegate.findTemplateSource(name);
    }

    @Override
    public long getLastModified(Object templateSource) {
        return delegate.getLastModified(templateSource);
    }

    @Override
    public Reader getReader(Object templateSource, String encoding) throws IOException {
        return delegate.getReader(templateSource, encoding);
    }

    @Override
    public void closeTemplateSource(Object templateSource) throws IOException {
        delegate.closeTemplateSource(templateSource);
    }
}
