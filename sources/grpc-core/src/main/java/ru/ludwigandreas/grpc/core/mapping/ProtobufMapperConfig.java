package ru.ludwigandreas.grpc.core.mapping;

import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.MapperConfig;
import org.mapstruct.NullValueCheckStrategy;

/**
 * The MapStruct configuration every mapper between a generated protobuf message and a model uses:
 * {@code @Mapper(config = ProtobufMapperConfig.class)}.
 *
 * <p>Two settings a protobuf builder needs and MapStruct's defaults do not give it, and the
 * conversions protobuf's well-known types need:
 * <ul>
 *   <li>{@link NullValueCheckStrategy#ALWAYS} - a builder's setter throws on {@code null}, so an
 *       absent model value has to leave the field unset rather than be set to {@code null};</li>
 *   <li>{@link CollectionMappingStrategy#ADDER_PREFERRED} - a repeated field has no setter, only
 *       {@code addX} and {@code addAllX};</li>
 *   <li>{@link ProtobufConversions} - {@code Instant} and {@code Duration} to and from
 *       {@code google.protobuf.Timestamp} and {@code google.protobuf.Duration}.</li>
 * </ul>
 *
 * <p>The rest - builders, {@code getXList}, {@code hasX} presence checks, {@code UNSPECIFIED} and
 * {@code UNRECOGNIZED} enum constants - is the protobuf SPI on {@code ludwig-service-parent}'s
 * annotation-processor path, which is why this works in a service and needs nothing in the mapper.
 */
@MapperConfig(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        uses = ProtobufConversions.class)
public interface ProtobufMapperConfig {
}
