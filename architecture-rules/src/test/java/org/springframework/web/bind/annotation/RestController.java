package org.springframework.web.bind.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.stereotype.Controller;

/** Test stub, meta-annotated like the real Spring annotation. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Controller
public @interface RestController {
}
