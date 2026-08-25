package com.github.oinsio.gnomish.adapter.git;

import org.jspecify.annotations.Nullable;

/**
 * The one place the factory's best-effort push points turn a {@link GitCommandResult} into the
 * words their WARN line opens with. Three outcomes must be readable from that line alone (UX3):
 * the remote rejected the push, the remote never answered, we were shut down. Before the named
 * termination existed all three read as "push failed", which is why an operator could not tell a
 * rejected push from a factory that was stopping.
 *
 * <p>Shared rather than triplicated because the wording is the requirement, not an incidental: the
 * three push points ({@link BestEffortPush}, {@link LifecyclePush}, {@link BranchPush}) differ only
 * in which action they name and which context they carry.
 *
 * <p>Implements FR8, NFR-O2, UX3 of bound-subprocess-commands.
 */
final class PushOutcome {

    private PushOutcome() {}

    /**
     * Describes what became of {@code result}, or reports that there is nothing to say.
     *
     * @param action what the push point calls its push, e.g. {@code "push"} or {@code "lifecycle
     *     push"}; never blank
     * @param result the push's raw result
     * @return the WARN line's opening phrase, or {@code null} when the push ran to a clean exit and
     *     the caller must log nothing at all
     */
    static @Nullable String describe(String action, GitCommandResult result) {
        return switch (result.termination()) {
            case TIMED_OUT -> action + " timed out";
            case INTERRUPTED -> action + " was interrupted";
            case EXITED -> result.exitCode() == 0 ? null : action + " failed";
        };
    }
}
