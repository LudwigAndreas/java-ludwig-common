package ru.ludwigandreas.security.unit;

import com.querydsl.core.types.PathMetadataFactory;
import com.querydsl.core.types.dsl.EntityPathBase;
import com.querydsl.core.types.dsl.PathInits;
import com.querydsl.core.types.dsl.SetPath;
import com.querydsl.core.types.dsl.StringPath;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A resource with both an owner column and a to-many association, plus the Q-types that the QueryDSL
 * annotation processor would generate for it.
 *
 * <p>Written by hand rather than generated because this module has no JPA dependency and therefore no
 * APT round - and a hand-written Q-type is a better fixture anyway: it makes visible exactly which
 * QueryDSL shapes the bindings are expected to accept.
 */
final class OrderFixture {

    private OrderFixture() {
    }

    record Mention(String userId) {
    }

    static final class Order {

        private final String createdBy;
        private final Set<Mention> mentions = new LinkedHashSet<>();

        Order(String createdBy, String... mentionedUserIds) {
            this.createdBy = createdBy;
            for (String userId : mentionedUserIds) {
                mentions.add(new Mention(userId));
            }
        }

        String getCreatedBy() {
            return createdBy;
        }

        Set<Mention> getMentions() {
            return mentions;
        }

        java.util.List<String> mentionedUserIds() {
            return mentions.stream().map(Mention::userId).toList();
        }
    }

    static final class QMention extends EntityPathBase<Mention> {

        final StringPath userId = createString("userId");

        QMention(com.querydsl.core.types.PathMetadata metadata) {
            super(Mention.class, metadata);
        }
    }

    static final class QOrder extends EntityPathBase<Order> {

        final StringPath createdBy = createString("createdBy");

        final SetPath<Mention, QMention> mentions =
                this.<Mention, QMention>createSet("mentions", Mention.class, QMention.class, PathInits.DIRECT2);

        QOrder(String variable) {
            super(Order.class, PathMetadataFactory.forVariable(variable));
        }
    }

    static final QOrder ORDER = new QOrder("order");
}
