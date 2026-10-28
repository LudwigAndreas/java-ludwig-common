package ru.ludwigandreas.security.admin;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test stub of security-spring-boot-starter's annotation of the same name. A stub and not the real one because the
 * rules match it by name, and this module takes no dependency on the security starter.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AdminAuthenticated {

}
