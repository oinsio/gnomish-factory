package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Git in a round's working copy, wherever the working copy lives: the host worktree through the
 * factory's {@link GitRunner}, or the box's through its exec ({@link InBoxGitCommand}, hooks off at
 * argv level). The one role {@link PendingStateLag} reads and restores through, so the lag a pending
 * commit leaves is settled by one rule on both media (design D11 of extract-agent-round-runner, as
 * amended 2026-11-25) rather than by a host rule and a box copy of it.
 *
 * <p>Implements FR10 of extract-agent-round-runner.
 */
interface WorkingCopyGit {

    /**
     * Runs one git command in the working copy.
     *
     * @param args the git subcommand and its arguments
     * @return the outcome; never null
     */
    GitCommandResult run(String... args);

    /** The host worktree's git, through the factory's runner. */
    static WorkingCopyGit host(GitRunner runner, Path worktree) {
        return args -> runner.run(worktree, args);
    }

    /**
     * The box's git, through its exec. The box merges its streams, so the whole output is the result's
     * stdout and its stderr is empty: every caller reads an object id, which a failure never prints.
     */
    static WorkingCopyGit inBox(TaskExecutionEnvironment environment) {
        InBoxGitCommand command = new InBoxGitCommand(environment);
        return args -> {
            // The arguments travel as positional parameters ($1…), never interpolated into the script.
            List<String> positional = new ArrayList<>(List.of("gnomish"));
            positional.addAll(List.of(args));
            InBoxGitCommand.Outcome outcome =
                    command.run("in-box working-copy git", "git -c core.hooksPath= \"$@\"", positional);
            return new GitCommandResult(
                    outcome.exitCode(), outcome.output(), UntrustedText.container(""), outcome.termination());
        };
    }
}
