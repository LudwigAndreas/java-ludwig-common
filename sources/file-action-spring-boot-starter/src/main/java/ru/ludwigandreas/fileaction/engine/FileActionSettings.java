package ru.ludwigandreas.fileaction.engine;

import java.time.Duration;
import ru.ludwigandreas.fileaction.api.ScanningMode;

/**
 * The module-wide settings the engine needs, resolved once and handed to it.
 *
 * <h2>Why the engine does not read {@code FileActionProperties}</h2>
 *
 * <p>It did, and that made a package cycle: {@code config} wires {@code web}, {@code web} calls {@code engine},
 * and {@code engine} read {@code config}. ArchUnit's cycle rule found it, and the rule was right about more than
 * the cycle - a configuration tree is a Spring binding artefact, with nullable override fields and a shape that
 * exists for YAML's benefit, and an engine that reads one is an engine that cannot be constructed in a test
 * without building that tree.
 *
 * <p>So the config layer resolves the tree into this and hands it over. The engine takes settings; it does not
 * take configuration.
 *
 * @param uploadsPrefix  the object-store prefix submitted files are written under
 * @param artifactsPrefix the prefix generated artifacts are written under
 * @param retention      how long a terminal submission's stored bytes are kept
 * @param scanningMode   whether a scanner is required, optional or off
 */
public record FileActionSettings(String uploadsPrefix, String artifactsPrefix, Duration retention,
                                 ScanningMode scanningMode) {

    /** Rejects settings the engine cannot work with; the validator has already refused these at startup. */
    public FileActionSettings {
        if (uploadsPrefix == null || uploadsPrefix.isBlank()) {
            throw new IllegalArgumentException("the uploads prefix is required");
        }
        if (artifactsPrefix == null || artifactsPrefix.isBlank()) {
            throw new IllegalArgumentException("the artifacts prefix is required");
        }
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive, was " + retention);
        }
        scanningMode = scanningMode == null ? ScanningMode.REQUIRED : scanningMode;
    }
}
