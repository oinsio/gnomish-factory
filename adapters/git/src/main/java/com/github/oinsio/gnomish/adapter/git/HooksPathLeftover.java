package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import java.nio.file.Path;

/**
 * The one reader of the {@code core.hooksPath} line factory versions before
 * own-git-invocation-policy wrote into a clone's own configuration
 * ({@code <clone>/.git/gnomish-empty-hooks}). That change stopped writing it and removes nothing
 * (its UX2), so {@code doctor} names the line and the command that removes it (design D6 of
 * add-doctor-command).
 *
 * <p>The read is {@code git config --local --get core.hooksPath} through the factory's runner.
 * {@code --local} is required: the runner applies the invocation policy, whose command-scope
 * {@code core.hooksPath=/dev/null} an unscoped {@code --get} would report. Only a value ending in
 * {@link #LEFTOVER_SUFFIX} is the leftover; any other value is an operator's own hooks path and is
 * not reported. A {@code --get} is a read, which the runner's configuration-write refusal lets
 * through; this class writes nothing.
 *
 * <p>Implements FR8 of add-doctor-command; UX2 of own-git-invocation-policy.
 */
@UntrustedParser
public final class HooksPathLeftover {

    /** The tail of the path earlier factory versions wrote: {@code <clone>/.git/gnomish-empty-hooks}. */
    static final String LEFTOVER_SUFFIX = "/gnomish-empty-hooks";

    /** {@code git config --get} exits 1 when the key is not set. */
    private static final int KEY_NOT_SET = 1;

    private final GitRunner runner;

    /**
     * @param runner the one git subprocess runner every git-backed port shares
     */
    public HooksPathLeftover(GitRunner runner) {
        this.runner = runner;
    }

    /**
     * Reads {@code clone}'s local {@code core.hooksPath} and judges it, refusing nothing and
     * logging nothing. The stdout is parsed into one fact — whether the trimmed value ends in
     * {@link #LEFTOVER_SUFFIX} — and otherwise kept as captured.
     *
     * @param clone the working directory of the registered clone
     * @return {@link HooksPathVerdict.Leftover} for the leftover line, {@link HooksPathVerdict.Clear}
     *     for an unset key or an operator's own path, {@link HooksPathVerdict.Unread} when git
     *     answered neither
     */
    public HooksPathVerdict assess(Path clone) {
        GitCommandResult result = runner.run(clone, "config", "--local", "--get", "core.hooksPath");
        if (result.succeeded()) {
            return result.stdout().forParsing().trim().endsWith(LEFTOVER_SUFFIX)
                    ? new HooksPathVerdict.Leftover(result.stdout())
                    : new HooksPathVerdict.Clear();
        }
        if (result.termination() == Termination.EXITED && result.exitCode() == KEY_NOT_SET) {
            return new HooksPathVerdict.Clear();
        }
        return new HooksPathVerdict.Unread(result.failureDetail("local core.hooksPath read"));
    }
}
