package ru.ludwigandreas.hotreload.properties;

import org.yaml.snakeyaml.Yaml;
import ru.ludwigandreas.hotreload.core.FileBackedSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * A {@link FileBackedSource} over a {@code .properties}, {@code .yml} or {@code .yaml} file - the format
 * is picked from the file extension. Reading a file that has been deleted or is momentarily empty (as
 * can happen mid-way through a Kubernetes ConfigMap/Secret volume remount) throws, which {@link
 * ru.ludwigandreas.hotreload.core.SourceReloadCoordinator} treats as a failed reload: the previously
 * loaded content keeps serving until a subsequent reload succeeds.
 */
public class PropertyFileSource implements FileBackedSource {

    private final Path path;
    private final String keyPrefix;

    public PropertyFileSource(Path path) {
        this(path, "");
    }

    /** @param keyPrefix prepended (as-is) to every flattened key, e.g. {@code "myapp."}; empty for none */
    public PropertyFileSource(Path path, String keyPrefix) {
        this.path = path;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
    }

    @Override
    public String id() {
        return "file:" + path.toAbsolutePath().normalize();
    }

    @Override
    public Path path() {
        return path;
    }

    @Override
    public Map<String, Object> load() throws IOException {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, Object> flat = fileName.endsWith(".yml") || fileName.endsWith(".yaml")
                ? loadYaml()
                : loadProperties();

        if (keyPrefix.isEmpty()) {
            return flat;
        }
        Map<String, Object> prefixed = new LinkedHashMap<>();
        flat.forEach((key, value) -> prefixed.put(keyPrefix + key, value));
        return prefixed;
    }

    private Map<String, Object> loadProperties() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            result.put(name, properties.getProperty(name));
        }
        return result;
    }

    private Map<String, Object> loadYaml() throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            Object document = new Yaml().load(in);
            return YamlFlattener.flatten(document);
        }
    }
}
