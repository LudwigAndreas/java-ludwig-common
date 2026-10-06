package ru.ludwigandreas.pat.exchange;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Teaches Jackson to omit the null fields of an inactive introspection response.
 *
 * <h2>Why a mixin rather than an annotation on the record</h2>
 *
 * <p>Because the record lives in {@code pat-core}, which has <b>zero dependencies</b> and therefore no
 * Jackson to annotate with. That constraint is load-bearing - it is what lets
 * {@code security-spring-boot-starter}, near the bottom of the reactor, depend on {@code pat-core} at
 * all - so the answer is not to relax it but to put the serialization concern in the module that already
 * has Jackson.
 *
 * <p>A mixin is the right shape for that, and specifically <b>not</b> a second definition of the field
 * names: it says only "omit nulls" and names no field. Mapping readable Java names onto wire names here
 * would have been the duplication {@code PatIntrospectionResponse} exists to prevent; declaring an
 * inclusion policy is not.
 *
 * <h2>What it buys</h2>
 *
 * <p>Without it, an inactive response serializes as
 * {@code {"active":false,"sub":null,"scope":null,"aud":null,"patId":null,"exp":null}}. That is still
 * byte-for-byte identical for every cause, so the no-oracle property holds either way - which is worth
 * being clear about, because this mixin is not a security fix.
 *
 * <p>What it buys is RFC 7662's own shape, {@code {"active":false}}, and a smaller surface: a response
 * with five null fields is a response somebody can add a sixth non-null field to without it looking out
 * of place. {@code UniformIntrospectionFailureIT} asserts the minimal form, so that addition would fail
 * a test rather than pass review.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class PatIntrospectionJsonMixin {
}
