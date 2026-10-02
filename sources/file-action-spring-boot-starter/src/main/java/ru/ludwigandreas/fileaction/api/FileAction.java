package ru.ludwigandreas.fileaction.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.stereotype.Component;

/**
 * Marks a bean as the code half of one file action, and names the configuration block it belongs to.
 *
 * <p>The value must match a key under {@code ludwig.file-action.actions}. A bean naming an action
 * nobody configured, or a configured action with no bean, is refused at startup by
 * {@code FileActionConfigurationValidator} - not by the first user who drags a file in.
 *
 * <p>The annotated bean must also be a {@link FileActionHandler}. That cannot be expressed in the
 * annotation's own type, so it is checked at startup along with everything else: an annotation whose
 * contract is only written down is one that gets applied to the wrong bean eventually.
 */
@Documented
@Component
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FileAction {

    /**
     * The action name.
     *
     * @return a key under {@code ludwig.file-action.actions}
     */
    String value();
}
