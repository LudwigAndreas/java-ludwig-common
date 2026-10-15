package ru.ludwigandreas.grpc.server;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test stub of grpc-server-spring-boot-starter's stereotype. A stub and not the real one because
 * that starter depends on this module at test scope, and the edge cannot run both ways.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LudwigGrpcService {
}
