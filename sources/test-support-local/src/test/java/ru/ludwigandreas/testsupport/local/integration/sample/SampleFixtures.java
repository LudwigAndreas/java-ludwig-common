package ru.ludwigandreas.testsupport.local.integration.sample;

import org.springframework.context.annotation.Import;

/**
 * The sample's seed fixtures, named once.
 *
 * <p>Not a {@code @Configuration} and the fixtures are not {@code @Component}s, so a context only has
 * them when it asks: {@code LocalRun.fixtures(SampleFixtures.class)}, or an {@code @Import} in a test.
 */
@Import({WidgetSeed.class, AnnotationSeed.class})
public class SampleFixtures {
}
