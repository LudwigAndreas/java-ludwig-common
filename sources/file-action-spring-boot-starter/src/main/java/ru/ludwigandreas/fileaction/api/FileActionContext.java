package ru.ludwigandreas.fileaction.api;

import java.util.UUID;
import ru.ludwigandreas.webcore.preference.UserPreferences;

/**
 * What a handler is told about the submission it is applying, beyond the rows themselves.
 *
 * <p>Deliberately small. A handler that needs more than this is reaching for something the module
 * should be giving it through a row or through its own injected collaborators, and widening this record
 * is how a handler ends up coupled to the module's internals.
 *
 * @param submissionId  the submission, which is also the operation id a client is polling. A handler
 *                      writing its own rows should record it, because it is the only link from the
 *                      domain data back to the file it came from and to who uploaded it
 * @param action        the action name, for a handler that serves more than one
 * @param filename      the name the client sent, for a message or a receipt. Never used to decide the
 *                      format - that comes from the content's magic bytes, because users rename files
 * @param correlationId the correlation id of the request that submitted the file, so a log line a
 *                      handler writes joins up with the module's own
 * @param preferences   the submitting caller's locale and zone. Already bound as ambient state on the
 *                      thread running the handler, so {@code UserPreferences.current()} returns this;
 *                      it is here as well because a handler that hands work to another thread must pass
 *                      it explicitly and {@code bind()} it there
 * @param dryRun        whether this invocation must not change anything. True for a {@code VALIDATE_ONLY}
 *                      action and for the validation pass of a {@code CONFIRM} action: a handler that
 *                      ignores it will apply a submission the user has not yet approved
 */
public record FileActionContext(UUID submissionId, String action, String filename,
                                String correlationId, UserPreferences preferences, boolean dryRun) {

    /** Rejects a context that cannot identify its submission or its action. */
    public FileActionContext {
        if (submissionId == null) {
            throw new IllegalArgumentException("A FileActionContext needs its submission id");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("A FileActionContext needs its action name");
        }
        preferences = preferences == null ? UserPreferences.FALLBACK : preferences;
    }
}
