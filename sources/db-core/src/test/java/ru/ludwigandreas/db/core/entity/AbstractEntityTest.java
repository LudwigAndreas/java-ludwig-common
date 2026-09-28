package ru.ludwigandreas.db.core.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractEntityTest {

    static class TestEntity extends AbstractEntity<Long> {
        private final Long id;

        TestEntity(Long id) {
            this.id = id;
        }

        @Override
        public Long getId() {
            return id;
        }
    }

    static class OtherEntity extends AbstractEntity<Long> {
        private final Long id;

        OtherEntity(Long id) {
            this.id = id;
        }

        @Override
        public Long getId() {
            return id;
        }
    }

    @Test
    void entitiesWithSameClassAndIdAreEqual() {
        assertThat(new TestEntity(1L)).isEqualTo(new TestEntity(1L));
    }

    @Test
    void entitiesWithDifferentIdsAreNotEqual() {
        assertThat(new TestEntity(1L)).isNotEqualTo(new TestEntity(2L));
    }

    @Test
    void entitiesOfDifferentClassesAreNeverEqualEvenWithSameId() {
        assertThat(new TestEntity(1L)).isNotEqualTo(new OtherEntity(1L));
    }

    @Test
    void entitiesWithNullIdAreNeverEqual() {
        assertThat(new TestEntity(null)).isNotEqualTo(new TestEntity(null));
    }

    @Test
    void hashCodeIsStableRegardlessOfId() {
        assertThat(new TestEntity(null).hashCode())
                .isEqualTo(new TestEntity(1L).hashCode())
                .isEqualTo(TestEntity.class.hashCode());
    }

    @Test
    void toStringIncludesClassNameAndId() {
        assertThat(new TestEntity(1L).toString()).isEqualTo("TestEntity(id=1)");
    }
}
