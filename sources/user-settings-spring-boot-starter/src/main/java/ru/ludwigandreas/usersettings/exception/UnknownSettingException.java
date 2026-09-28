package ru.ludwigandreas.usersettings.exception;

import lombok.Getter;
import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * A setting was referenced that no {@code SettingDefinitionSource} registered.
 *
 * <p>Reaching this at runtime means a definition constant exists in code but is not contributed by
 * any source. The registry validates everything it is given at startup, so the one case it cannot
 * catch earlier is a definition nobody handed it - detecting that would require scanning the
 * classpath for {@code SettingDefinition} constants, which would then also find the ones a service
 * declared deliberately and does not use here.
 *
 * <p>So the guarantee is precisely: every <em>registered</em> definition is checked before the
 * context starts, and an unregistered one fails loudly and specifically at first reference rather
 * than resolving to a default and looking like a data problem.
 */
@Getter
public class UnknownSettingException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    private final String settingKey;

    /**
     * Reports a setting nothing registered.
     *
     * @param settingKey published as the {@code setting} member, so the client sees which one
     */
    public UnknownSettingException(String settingKey) {
        super(ProblemStatus.NOT_FOUND, SettingProblemCodes.UNKNOWN_SETTING, settingKey);
        this.settingKey = settingKey;
        withProperty("setting", settingKey);
    }
}
