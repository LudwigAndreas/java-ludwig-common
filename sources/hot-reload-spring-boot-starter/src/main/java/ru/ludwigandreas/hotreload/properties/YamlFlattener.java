package ru.ludwigandreas.hotreload.properties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattens a parsed YAML document into the dotted/indexed key notation Spring's {@code Environment}
 * and {@code @ConfigurationProperties} binder expect (e.g. {@code foo.bar[0].baz}), the same convention
 * {@code YamlPropertiesFactoryBean} uses.
 */
final class YamlFlattener {

    private YamlFlattener() {
    }

    static Map<String, Object> flatten(Object root) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (root != null) {
            flattenInto("", root, result);
        }
        return result;
    }

    private static void flattenInto(String prefix, Object value, Map<String, Object> out) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty() && !prefix.isEmpty()) {
                out.put(prefix, "");
                return;
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String newPrefix = prefix.isEmpty() ? key : prefix + "." + key;
                flattenInto(newPrefix, entry.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                out.put(prefix, "");
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                flattenInto(prefix + "[" + i + "]", list.get(i), out);
            }
        } else {
            out.put(prefix, value);
        }
    }
}
