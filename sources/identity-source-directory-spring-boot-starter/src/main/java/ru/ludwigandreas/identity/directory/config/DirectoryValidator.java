package ru.ludwigandreas.identity.directory.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import ru.ludwigandreas.identity.directory.IdentityDirectory;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Refuses two directories for one source: their sweeps would each disable the other's users. That a directory's
 * source is declared is checked by the projection, through the directory's feed.
 */
public class DirectoryValidator implements SmartInitializingSingleton {

    private final ObjectProvider<IdentityDirectory> directories;

    /**
     * Creates the validator.
     *
     * @param directories every directory bean
     */
    public DirectoryValidator(ObjectProvider<IdentityDirectory> directories) {
        this.directories = directories;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, String> seen = new HashMap<>();
        directories.orderedStream().forEach(directory -> {
            String type = directory.getClass().getName();
            String previous = seen.putIfAbsent(directory.source(), type);
            if (previous != null) {
                throw new SecurityConfigurationException("Two identity directories name source '"
                        + directory.source() + "' (" + previous + ", " + type + "); a source has one directory");
            }
        });
    }
}
