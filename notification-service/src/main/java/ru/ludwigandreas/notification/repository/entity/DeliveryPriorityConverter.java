package ru.ludwigandreas.notification.repository.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Stores {@link DeliveryPriority} as its orderable weight rather than as its constant name.
 *
 * <p>{@code @Enumerated(STRING)} would put {@code 'HIGH'}, {@code 'NORMAL'} and {@code 'BULK'} in a
 * {@code varchar}, and the claim query's {@code ORDER BY priority DESC} would then sort them
 * alphabetically - {@code NORMAL} ahead of {@code HIGH} - which silently inverts the whole point of
 * having lanes. {@code @Enumerated(ORDINAL)} would order correctly today and break the first time
 * somebody inserts a constant in the middle of the enum. An explicit weight is the only option that
 * survives both.
 *
 * <p>Applied automatically to every {@code DeliveryPriority} attribute ({@code autoApply}), so no
 * entity has to remember it.
 */
@Converter(autoApply = true)
public class DeliveryPriorityConverter implements AttributeConverter<DeliveryPriority, Integer> {

    @Override
    public Integer convertToDatabaseColumn(DeliveryPriority attribute) {
        return attribute == null ? null : attribute.getWeight();
    }

    @Override
    public DeliveryPriority convertToEntityAttribute(Integer dbData) {
        return dbData == null ? null : DeliveryPriority.ofWeight(dbData);
    }
}
