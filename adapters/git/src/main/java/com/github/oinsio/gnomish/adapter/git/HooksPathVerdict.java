package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The verdict {@link HooksPathLeftover#assess} returns about a clone's local {@code core.hooksPath}:
 * clear (unset, or an operator's own path), the leftover line an earlier factory version wrote, or
 * not read at all. It refuses nothing and logs nothing; the {@code doctor} check maps it to ok, warn
 * or fail (design D6 of add-doctor-command).
 *
 * <p>Implements FR8 of add-doctor-command; UX2 of own-git-invocation-policy.
 */
public sealed interface HooksPathVerdict {

    /**
     * The one command that removes the leftover line — printed by the reader, never run by the
     * factory, which writes nothing to a clone's configuration (UX2 of own-git-invocation-policy).
     */
    String FIX = "git config --local --unset core.hooksPath";

    /** The clone's local configuration holds no {@code core.hooksPath}, or one the operator set. */
    record Clear() implements HooksPathVerdict {}

    /**
     * The clone's local configuration holds the line an earlier factory version wrote.
     *
     * @param value the local value as git printed it
     */
    record Leftover(UntrustedText value) implements HooksPathVerdict {}

    /**
     * The local configuration could not be read: git timed out, was interrupted, or exited with
     * neither a value (0) nor "not set" (1).
     *
     * @param reason what went wrong, git's own words included
     */
    record Unread(UntrustedText reason) implements HooksPathVerdict {}
}
