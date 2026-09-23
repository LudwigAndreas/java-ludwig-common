package ru.ludwigandreas.usersettings.exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * One or more submitted values failed their definitions' validation.
 *
 * <h2>How this renders</h2>
 *
 * <p>The problem's {@code code} is the <em>violation's</em> code, not a generic
 * "settings validation failed" - so the sentence a caller reads is the specific one
 * ("Must be between 0 and 23") rather than a wrapper around it. That is why a service supplying its
 * own validator must also supply a bundle entry for the code it returns; the built-in validators'
 * codes ship with this module.
 *
 * <p>A bulk update validates every change before writing any, so several settings can be rejected at
 * once. The first rejection supplies the rendered sentence and the complete list is published as the
 * {@code violations} member, keyed by setting - which is what a settings form needs to attach each
 * message to the field that produced it.
 *
 * <h2>What is never here</h2>
 *
 * <p>The rejected value, for any setting, flagged as personal data or not. A problem document is
 * echoed into client logs, proxy logs and error trackers, and a rule that omitted the value only for
 * PII-flagged settings would be a rule that depends on every definition having been flagged
 * correctly. Omitting it always costs a little debuggability and removes a whole class of leak.
 */
@Getter
public class SettingValidationException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final transient List<RejectedSetting> rejected;

    /**
     * Reports every value a write was refused for.
     *
     * @param rejected at least one; the first supplies the rendered sentence, all are published
     */
    public SettingValidationException(List<RejectedSetting> rejected) {
        super(ProblemStatus.INVALID,
                rejected.get(0).violation().code(),
                rejected.get(0).violation().argArray());
        this.rejected = List.copyOf(rejected);
        withProperty("setting", rejected.get(0).settingKey());
        withProperty("violations", describe(this.rejected));
    }

    public static SettingValidationException of(String settingKey, SettingViolation violation) {
        return new SettingValidationException(List.of(new RejectedSetting(settingKey, violation)));
    }

    /**
     * Machine-readable, deliberately not localized: a client branches on {@code reason} and renders
     * its own text per field, exactly as it does for the {@code violations} member of a Bean
     * Validation failure.
     */
    private static List<Map<String, Object>> describe(List<RejectedSetting> rejected) {
        return rejected.stream()
                .map(entry -> {
                    Map<String, Object> described = new LinkedHashMap<>();
                    described.put("setting", entry.settingKey());
                    described.put("reason", entry.violation().code());
                    return Map.copyOf(described);
                })
                .toList();
    }
}
