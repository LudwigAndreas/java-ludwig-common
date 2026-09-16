package org.springframework.boot.autoconfigure;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.SpringBootConfiguration;

/** Test stub, meta-annotated like the real Spring Boot annotation. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@SpringBootConfiguration
public @interface SpringBootApplication {
}
