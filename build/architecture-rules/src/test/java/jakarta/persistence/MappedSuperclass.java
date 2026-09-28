package jakarta.persistence;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Test stub of the real annotation. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface MappedSuperclass {
}
