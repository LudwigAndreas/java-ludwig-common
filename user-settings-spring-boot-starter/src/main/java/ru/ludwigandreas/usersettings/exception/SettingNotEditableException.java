package ru.ludwigandreas.usersettings.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A user tried to change a setting whose definition declares {@code userEditable(false)}.
 *
 * <p>A 403 rather than a 404: the setting exists and the caller can read it, they simply may not set
 * it. Answering 404 to hide that would make a legitimate settings UI unable to tell "no such
 * setting" from "shown but locked", which is a distinction it has to render.
 *
 * <p>An administrator writing the same setting at the tenant or platform layer is unaffected - the
 * flag governs whether the <em>subject</em> may edit their own value, not whether the value can be
 * managed at all.
 */
@Getter
public class SettingNotEditableException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String settingKey;

    /**
     * Refuses a change to a setting its own subject may not edit.
     *
     * @param settingKey published as the {@code setting} member so a client can highlight it
     */
    public SettingNotEditableException(String settingKey) {
        super(ProblemStatus.FORBIDDEN, SettingProblemCodes.NOT_EDITABLE, settingKey);
        this.settingKey = settingKey;
        withProperty("setting", settingKey);
    }
}
