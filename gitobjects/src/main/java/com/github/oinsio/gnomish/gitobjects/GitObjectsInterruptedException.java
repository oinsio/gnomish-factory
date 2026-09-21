package com.github.oinsio.gnomish.gitobjects;

import java.io.Serial;

/**
 * Thrown when a git plumbing invocation did not run to its own exit because the calling thread was
 * interrupted — the wait was cut off, or the capped read of stdout was abandoned mid-stream.
 *
 * <p>A subtype rather than a message, because it is the one failure of this library that says
 * nothing about the repository: every other {@link GitObjectsException} reports what git answered,
 * while this one reports that git was never allowed to answer. A caller that degrades on an
 * unreadable document — a best-effort read that treats "absent or unparseable" as "nothing to
 * carry" — must not fold this outcome into that answer, or a shutdown lands as a fact about the
 * tip. Catching {@link GitObjectsException} still catches it, so a caller that already fails loudly
 * on any library failure is unaffected.
 *
 * <p>The bare-object counterpart of the subprocess seam's {@code BranchTipUnavailableException} one
 * layer up: same rule, stated for the medium that has no deadline of its own.
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public final class GitObjectsInterruptedException extends GitObjectsException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GitObjectsInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
