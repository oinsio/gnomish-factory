package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.baseref.RefNameSyntax;
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths;
import com.github.oinsio.gnomish.logtext.LogText;
import org.jspecify.annotations.Nullable;

/**
 * The content gate on {@code task.json}'s pinned base ref, run by {@link TaskJsonMapper#readDto}
 * after the version gate and the binder: a pinned {@code baseRef} must be a well-formed ref name
 * under {@link RefNameSyntax}, the one grammar every ref entering the process is held to.
 *
 * <p>Why at the read and not at the fetch: the pin is the name an autonomous resume narrow-fetches,
 * and it was written by another instance into a branch a human can push to. Checked here, a
 * malformed name is a fault of the document — the unreadable-envelope fact, the corrupt branch
 * shape, a diagnosis naming {@code task.json} — and the resume that would have built a refspec from
 * it is never reached. Refuse, not escape: the {@code git check-ref-format} posture.
 *
 * <p>Implements NFR-S3 of add-base-ref-resolution (task 12.2).
 */
final class PinnedRefGate {

    private PinnedRefGate() {}

    /**
     * Refuses a malformed pinned ref; an absent pin (a legacy or unpinned document) passes.
     *
     * @param baseRef the document's {@code baseRef} field, or {@code null} when it carries none
     * @throws MalformedStateFileException naming the document, the sanitized value and the rule
     */
    static void check(@Nullable String baseRef) {
        if (baseRef == null) {
            return;
        }
        String violation = RefNameSyntax.refNameViolation(baseRef).orElse(null);
        if (violation != null) {
            throw new MalformedStateFileException(
                    EnvelopePaths.TASK_FILE,
                    "baseRef '" + LogText.forLog(baseRef) + "' is not a well-formed ref name: " + violation);
        }
    }
}
