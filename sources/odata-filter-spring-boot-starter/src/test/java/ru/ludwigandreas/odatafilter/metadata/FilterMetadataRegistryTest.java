package ru.ludwigandreas.odatafilter.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;
import ru.ludwigandreas.odatafilter.annotation.Filterable;

/** Opt-in means opt-in: an entity nobody named is absent, and two entities cannot share a name. */
class FilterMetadataRegistryTest {

    @Entity
    @FilterPolicy(metadataName = "product")
    static class PublishedProduct {
        @Id
        private Long id;

        @Filterable
        private String sku;
    }

    @Entity
    @FilterPolicy(metadataName = "product")
    static class AlsoPublishedAsProduct {
        @Id
        private Long id;
    }

    /** Filterable fields, deliberately no metadataName - the common case, and it must stay invisible. */
    @Entity
    @FilterPolicy
    static class UnpublishedOrder {
        @Id
        private Long id;

        @Filterable
        private String number;
    }

    @Entity
    static class NoPolicyAtAll {
        @Id
        private Long id;
    }

    private static Metamodel metamodelOf(Class<?>... javaTypes) {
        Metamodel metamodel = Mockito.mock(Metamodel.class);
        Set<EntityType<?>> entities = Arrays.stream(javaTypes).map(javaType -> {
            EntityType<?> entityType = Mockito.mock(EntityType.class);
            Mockito.doReturn(javaType).when(entityType).getJavaType();
            return entityType;
        }).collect(Collectors.toSet());
        Mockito.doReturn(entities).when(metamodel).getEntities();
        return metamodel;
    }

    @Test
    @DisplayName("an entity that declares a published name is indexed under it")
    void indexesAPublishedEntity() {
        FilterMetadataRegistry registry = new FilterMetadataRegistry(metamodelOf(PublishedProduct.class));

        assertThat(registry.entityFor("product")).contains(PublishedProduct.class);
        assertThat(registry.publishedNames()).containsExactly("product");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("an entity with filterable fields but no published name is absent")
    void omitsAnUnpublishedEntity() {
        FilterMetadataRegistry registry = new FilterMetadataRegistry(
                metamodelOf(UnpublishedOrder.class, NoPolicyAtAll.class));

        assertThat(registry.publishedNames()).isEmpty();
        assertThat(registry.entityFor("unpublishedOrder")).isEmpty();
        assertThat(registry.entityFor("order")).isEmpty();
    }

    @Test
    @DisplayName("an unknown name and an unpublished entity are the same answer, on purpose")
    void doesNotDistinguishUnknownFromUnpublished() {
        FilterMetadataRegistry registry = new FilterMetadataRegistry(
                metamodelOf(PublishedProduct.class, UnpublishedOrder.class));

        // Telling them apart would make this endpoint a way to enumerate the entity model.
        assertThat(registry.entityFor("unpublishedOrder")).isEqualTo(registry.entityFor("nothing-like-this"));
    }

    @Test
    @DisplayName("two entities publishing one name fail startup, and the message names both classes")
    void refusesADuplicateName() {
        Metamodel metamodel = metamodelOf(PublishedProduct.class, AlsoPublishedAsProduct.class);

        // At startup, not at the first request: that is the earliest point both entities exist, and two
        // modules that never see each other cannot be checked by any single compilation.
        assertThatThrownBy(() -> new FilterMetadataRegistry(metamodel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("product")
                .hasMessageContaining("PublishedProduct")
                .hasMessageContaining("AlsoPublishedAsProduct");
    }

    @Test
    @DisplayName("an empty metamodel is an empty registry, not a failure")
    void toleratesNoEntities() {
        assertThat(new FilterMetadataRegistry(metamodelOf()).size()).isZero();
    }

    @Test
    @DisplayName("a name is matched after trimming, so a stray path space does not 404")
    void trimsTheName() {
        FilterMetadataRegistry registry = new FilterMetadataRegistry(metamodelOf(PublishedProduct.class));

        assertThat(registry.entityFor(" product ")).contains(PublishedProduct.class);
        assertThat(registry.entityFor(null)).isEmpty();
    }

    @Test
    @DisplayName("an entity type with no Java type is skipped rather than failing the walk")
    void skipsAnEntityWithNoJavaType() {
        Metamodel metamodel = Mockito.mock(Metamodel.class);
        EntityType<?> broken = Mockito.mock(EntityType.class);
        Mockito.doReturn(null).when(broken).getJavaType();
        Mockito.doReturn(Set.of(broken)).when(metamodel).getEntities();

        assertThat(new FilterMetadataRegistry(metamodel).size()).isZero();
    }

    @Test
    @DisplayName("the registry resolves names only - it never produces a document")
    void producesNoDocument() {
        // The design constraint, asserted on the shape that expresses it rather than on a method-name list:
        // if this class ever grows a method returning a document, it has started describing the policy
        // instead of pointing at the entity, and a second description is what drifts. Return types rather
        // than names because an instrumented class has synthetic methods ($jacocoInit) that a name list
        // cannot anticipate.
        List<Class<?>> returnTypes = Arrays.stream(FilterMetadataRegistry.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getReturnType)
                .toList();

        assertThat(returnTypes)
                .doesNotContain(FilterMetadata.class)
                .doesNotContain(FilterPropertyMetadata.class);
    }
}
