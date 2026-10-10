package ru.ludwigandreas.testsupport.local.integration.sample;

import java.util.List;
import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/**
 * Marks every widget as seeded, which only does anything when the widgets already exist.
 *
 * <p>The name is deliberate: it sorts before {@link WidgetSeed}, so the alphabetical tie-break alone
 * would run it first and leave the notes empty. A test that finds them filled has seen
 * {@link #after()} honoured.
 */
public class AnnotationSeed implements SeedFixture {

    /** The note this fixture writes. */
    public static final String NOTE = "seeded";

    private final WidgetRepository widgets;

    public AnnotationSeed(WidgetRepository widgets) {
        this.widgets = widgets;
    }

    @Override
    public void seed() {
        widgets.annotateAll(NOTE);
    }

    @Override
    public List<Class<? extends SeedFixture>> after() {
        return List.of(WidgetSeed.class);
    }
}
