package ru.ludwigandreas.usersettings.web;

import ru.ludwigandreas.usersettings.exception.SettingConfigurationException;
import ru.ludwigandreas.usersettings.exception.SettingProblemCodes;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Renders the one failure of this module that is not already a {@code LocalizedException}.
 *
 * <p>{@link SettingConfigurationException} is almost always a startup failure, and a startup failure
 * never reaches a problem document. The case that does is a definition mismatch caught at first use:
 * a service holding a {@code SettingDefinition} it declared but never registered, whose key is
 * registered under a different declaration. Without this mapper that comes back as an anonymous 500
 * with the generic "the request could not be processed" text, which tells an operator nothing; with
 * it, the response carries a code they can search for and the exception message still reaches the
 * log with the two declarations named.
 *
 * <p>Registered at the module default order so an application that wants different wording can put
 * its own mapper in front without knowing what order this one picked.
 */
public class SettingsProblemMapper implements ExceptionProblemMapper {

    @Override
    public boolean supports(Throwable exception) {
        return exception instanceof SettingConfigurationException;
    }

    @Override
    public ProblemDefinition map(Throwable exception) {
        return ProblemDefinition.of(ProblemStatus.INTERNAL, SettingProblemCodes.CONFIGURATION);
    }

    @Override
    public int getOrder() {
        return DEFAULT_MODULE_ORDER;
    }
}
