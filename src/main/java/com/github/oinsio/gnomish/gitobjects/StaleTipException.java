package com.github.oinsio.gnomish.gitobjects;

/**
 * Thrown when a commit's compare-and-swap precondition does not hold: the ref already exists when
 * creation was requested, or its tip differs from {@code expectedTip} — either at the pre-check or
 * at git's atomic {@code update-ref}. The ref is left unchanged; the caller (another factory
 * instance likely moved the branch) re-reads and retries (design D19, FR25).
 *
 * <p>Implements FR25 of add-sandbox-core.
 */
public final class StaleTipException extends GitObjectsException {

    public StaleTipException(String message) {
        super(message);
    }
}
