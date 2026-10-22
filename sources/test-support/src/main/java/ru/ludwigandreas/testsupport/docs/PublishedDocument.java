package ru.ludwigandreas.testsupport.docs;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The check behind every committed API document: generate, canonicalize, compare, and refresh on request.
 *
 * <p>Three properties make it a check rather than a chore, and all three are deliberate:
 *
 * <ul>
 *   <li><b>The document is canonicalized before comparison</b> - every object's members sorted, and what
 *       describes the test harness rather than a deployment removed. Generators walk beans and handler
 *       mappings in no promised order, and an unsorted document would produce diffs that are pure
 *       reordering; the first time that happens somebody refreshes without reading the diff, and the check
 *       is decorative. Member order carries no meaning in either format; array order does, so arrays are
 *       left alone.</li>
 *   <li><b>An ordinary build never rewrites the committed file.</b> Refreshing is opt-in through
 *       {@code -Dludwig.apidocs.write=true}, so the diff is always something the author asked for.</li>
 *   <li><b>A hand edit fails exactly as drift does</b>, because the two are indistinguishable from here and
 *       are wrong for the same reason.</li>
 * </ul>
 */
public final class PublishedDocument {

    /** Set to refresh the committed document. */
    public static final String WRITE_PROPERTY = "ludwig.apidocs.write";

    /** The two kinds of document, and what each loses to canonicalization. */
    public enum Kind {

        /**
         * OpenAPI: the {@code servers} block is removed. It is derived from the request the document was
         * fetched with, so it describes the test harness; committing {@code http://localhost} would be a
         * claim about where the service runs.
         */
        OPENAPI("openapi"),

        /**
         * AsyncAPI: every server's {@code host} is replaced by the server's name. The names are the clusters a
         * consumer has to know about; the hosts are test brokers on random ports. AsyncAPI requires a host, so
         * the name stands in for it rather than the host being removed.
         */
        ASYNCAPI("asyncapi");

        private final String extension;

        Kind(String extension) {
            this.extension = extension;
        }

        /**
         * The file name part, as in {@code <artifactId>.<extension>.yaml}.
         *
         * @return the extension
         */
        public String extension() {
            return extension;
        }
    }

    private PublishedDocument() {
    }

    /**
     * Compares a generated document with the committed one, or overwrites the committed one when
     * {@value #WRITE_PROPERTY} is set.
     *
     * <p>The generated copy is always written to {@code target/<kind>/<artifactId>.<kind>.yaml} first, so a
     * failing build leaves it where a {@code diff} can reach it.
     *
     * @param artifactId the service the document describes
     * @param kind       which kind of document
     * @param document   the generated document
     * @param testClass  the test that generates it, named in the refresh command
     */
    public static void check(String artifactId, Kind kind, JsonNode document, Class<?> testClass) {
        String generated = canonical(document, kind);
        String fileName = artifactId + "." + kind.extension() + ".yaml";
        String refresh = "mvn -pl :%s verify -Dit.test=%s -DfailIfNoTests=false -D%s=true"
                .formatted(artifactId, testClass.getSimpleName(), WRITE_PROPERTY);
        try {
            Path target = Paths.get("target", kind.extension(), fileName);
            Files.createDirectories(target.getParent());
            Files.writeString(target, generated, StandardCharsets.UTF_8);

            Path committed = repositoryRoot().resolve("docs").resolve("api").resolve(fileName);
            if (Boolean.parseBoolean(System.getProperty(WRITE_PROPERTY))) {
                Files.createDirectories(committed.getParent());
                Files.writeString(committed, generated, StandardCharsets.UTF_8);
                return;
            }
            if (!Files.exists(committed)) {
                throw new AssertionError("the committed copy is missing: %s%nGenerate it with:%n  %s"
                        .formatted(committed, refresh));
            }
            if (!Files.readString(committed, StandardCharsets.UTF_8).equals(generated)) {
                throw new AssertionError(("this service's %s surface has changed but %s was not refreshed.%n"
                        + "See what changed:%n  diff %s %s%nRefresh it with:%n  %s%n"
                        + "then read the diff before committing it.")
                        .formatted(kind == Kind.OPENAPI ? "HTTP" : "Kafka", committed, committed,
                                target.toAbsolutePath(), refresh));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The document in its committed form.
     *
     * @param document the generated document
     * @param kind     which kind of document
     * @return canonical YAML
     */
    public static String canonical(JsonNode document, Kind kind) {
        Object sorted = sortedTree(document);
        if (sorted instanceof Map<?, ?> map) {
            if (kind == Kind.OPENAPI) {
                map.remove("servers");
            } else if (map.get("servers") instanceof Map<?, ?> servers) {
                servers.forEach((name, server) -> {
                    if (server instanceof Map<?, ?> fields) {
                        // AsyncAPI requires a host; the server's name stands in, so no address is committed.
                        @SuppressWarnings("unchecked")
                        Map<String, Object> writable = (Map<String, Object>) fields;
                        writable.put("host", name);
                    }
                });
            }
        }
        return yaml().dump(sorted);
    }

    private static Yaml yaml() {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        // Long descriptions are emitted on one line rather than folded. Folding is width-dependent, and a
        // width is one more thing that has to agree across machines for the comparison to hold.
        options.setSplitLines(false);
        return new Yaml(options);
    }

    /**
     * The document as plain collections, every object's members in key order. {@link TreeMap} produces the
     * canonical form; arrays keep their order because it carries meaning ({@code required}, {@code enum}).
     */
    private static Object sortedTree(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                sorted.put(field.getKey(), sortedTree(field.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> values = new ArrayList<>(node.size());
            node.forEach(child -> values.add(sortedTree(child)));
            return values;
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNull()) {
            return null;
        }
        return node.asText();
    }

    /**
     * The repository root, found by walking up for the two directories that only the root has.
     *
     * <p>Surefire and failsafe run with the module directory as the working directory, so the path to
     * {@code docs/} is relative to a location a test should not hard-code. Both {@code docs/} and
     * {@code openspec/} exist only at the root, so requiring both is unambiguous.
     */
    private static Path repositoryRoot() throws IOException {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("docs")) && Files.isDirectory(candidate.resolve("openspec"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("no repository root above " + Paths.get("").toAbsolutePath()
                + " holds both docs/ and openspec/");
    }
}
