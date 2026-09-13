package com.github.oinsio.gnomish.adapter.git.state;

import java.io.Serial;

/**
 * A {@code .gnomish-task/} state file that parsed and bound, but whose content fails a rule the
 * reader holds it to — the third refusal of the document read, beside the version gate's {@code
 * UnsupportedStateFileVersionException} and the binder's {@code UncheckedIOException}. The one such
 * rule today is the base pin's ref name (NFR-S3 of add-base-ref-resolution, task 12.2).
 *
 * <p>The message names the document, the field and the violated rule, and is safe to carry into a
 * log or a report as it is: {@link com.github.oinsio.gnomish.adapter.git.BranchTipFactsReader}
 * folds it into an unreadable-envelope fact, which the branch shape classifier turns into the
 * corrupt shape every reader refuses on with that message as its diagnosis.
 */
public final class MalformedStateFileException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param fileName the state file's name, e.g. {@code task.json}; never null
     * @param detail what is wrong with it — the field, its sanitized value and the rule; never null
     */
    public MalformedStateFileException(String fileName, String detail) {
        super(fileName + ": " + detail);
    }
}
