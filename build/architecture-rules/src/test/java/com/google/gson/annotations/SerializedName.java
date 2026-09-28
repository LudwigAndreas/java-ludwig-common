package com.google.gson.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test stub of Gson's annotation. Gson rather than Jackson because the tests parse this library's own
 * JSON report with a real Jackson on the classpath, and a stub sharing its package would clash.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface SerializedName {

    String value() default "";
}
