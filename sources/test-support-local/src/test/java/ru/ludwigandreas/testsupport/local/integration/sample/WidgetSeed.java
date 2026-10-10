package ru.ludwigandreas.testsupport.local.integration.sample;

import ru.ludwigandreas.testsupport.local.fixture.SeedFixture;

/** Seeds the two widgets everything else in the sample assumes. */
public class WidgetSeed implements SeedFixture {

    private final WidgetRepository widgets;

    public WidgetSeed(WidgetRepository widgets) {
        this.widgets = widgets;
    }

    @Override
    public void seed() {
        widgets.save(new Widget("alpha", null));
        widgets.save(new Widget("beta", null));
    }
}
