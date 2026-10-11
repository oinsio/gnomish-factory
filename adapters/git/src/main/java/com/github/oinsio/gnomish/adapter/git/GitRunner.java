package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.gittransfer.GitTransfer;
import java.nio.file.Path;

/**
 * The role every holder of the git runner plays against it: one {@link GitCommandResult} per git
 * invocation, by an untyped argv or by the transfer owner's value. {@link GitProcessRunner} is the
 * one production implementation — argv, environment, deadlines, the clone mutation lock all stay
 * there — and {@code ScriptedGit} in this package's specs is the one fake, so a class above the
 * runner is specified in process rather than through a stand-in binary (design D27 of
 * supervise-daemon-loops-and-embed-dashboard; ADR 0015, decision 6).
 *
 * <p>Package-private, like the result it returns: the composition root holds the concrete runner
 * and hands it to this package's constructors, which take the role.
 *
 * <p>Implements FR27 of supervise-daemon-loops-and-embed-dashboard.
 */
interface GitRunner {

    /**
     * Runs one git command given as a direct argv.
     *
     * @param cwd the working directory for the git process
     * @param args the git subcommand and its arguments
     * @return the captured exit code, separate stdout/stderr, and the named termination
     * @see GitProcessRunner#run(Path, String...)
     */
    GitCommandResult run(Path cwd, String... args);

    /**
     * Runs the transfer owner's value.
     *
     * @param cwd the working directory for the git process
     * @param transfer the owner's value
     * @return the captured exit code, separate stdout/stderr, and the named termination
     * @see GitProcessRunner#run(Path, GitTransfer)
     */
    GitCommandResult run(Path cwd, GitTransfer transfer);
}
