package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.DefaultBranchDiscovery;
import com.github.oinsio.gnomish.baseref.DefaultBranch;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.subprocess.Termination;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Asks {@code origin} which branch it calls its default — {@code git ls-remote --symref origin
 * HEAD} — for the zero-configuration tier of base resolution: a project that configures no base at
 * all branches from whatever the remote reports, and a repository renamed from {@code main} to
 * {@code develop} is followed with no configuration change (FR5).
 *
 * <p>Deliberately a remote read, never {@code refs/remotes/origin/HEAD}: the local symref is
 * written once at clone time and never refreshed, so a factory reading it would keep branching
 * from a default the project abandoned months ago — the exact staleness this change exists to end.
 *
 * <p>One remote round-trip, retried only while the question is unsettled: an unanswered origin is
 * {@link DefaultBranchDiscovery.Unavailable} and re-asked under {@link GitInfrastructureRetry},
 * while "no origin configured" and "origin named no default branch" are facts and are returned on
 * the first attempt.
 *
 * <p>Implements FR5, FR9, NFR-P1 of add-base-ref-resolution.
 */
@UntrustedParser
public final class RemoteDefaultBranch {

    /**
     * The whole head of a symref line naming a branch: git's own {@code "ref: "} prefix plus the
     * only namespace a default branch can live in. Matched as one string rather than as a prefix
     * test followed by a namespace test — two overlapping decisions where the parser makes one.
     */
    private static final String SYMREF_HEAD = "ref: refs/heads/";

    private final GitProcessRunner runner;
    private final OriginRemote origin;
    private final GitInfrastructureRetry retry;

    /**
     * @param runner the git subprocess seam; never null
     * @param retry the infrastructure budget an unanswered origin is re-asked under; never null
     */
    public RemoteDefaultBranch(GitProcessRunner runner, GitInfrastructureRetry retry) {
        this.runner = runner;
        this.origin = new OriginRemote(runner);
        this.retry = retry;
    }

    /**
     * Discovers the default branch {@code origin} reports for the clone at {@code cloneDir}.
     *
     * @param cloneDir the factory clone the read runs from; never null
     * @return the branch origin named, or why no name could be established
     */
    public DefaultBranchDiscovery discover(Path cloneDir) {
        return retry.until(
                () -> attempt(cloneDir), discovered -> !(discovered instanceof DefaultBranchDiscovery.Unavailable));
    }

    private DefaultBranchDiscovery attempt(Path cloneDir) {
        GitCommandResult read = LsRemote.symrefHead(runner, cloneDir);
        if (read.termination() != Termination.EXITED || read.exitCode() != 0) {
            // A failing read has two very different causes, and only the local config tells them
            // apart: a clone with no origin at all was never going to answer, and re-asking it is
            // spending an infrastructure budget on a settled fact.
            if (!origin.isConfigured(cloneDir)) {
                return new DefaultBranchDiscovery.NoRemote();
            }
            return new DefaultBranchDiscovery.Unavailable(read.failureDetail("default-branch read"));
        }
        return symrefBranch(read.stdout().forParsing())
                .map(RemoteDefaultBranch::discovered)
                .orElseGet(() -> new DefaultBranchDiscovery.Undetermined(UntrustedText.factory(
                        "origin answered but named no default branch: its HEAD points at no ref")));
    }

    /**
     * Holds the name origin reported to {@link DefaultBranch}'s own rules before it is discovered:
     * the name goes on to the refresh fetch as a refspec, and it is subprocess output from a remote
     * a human administers, so a name those rules refuse is a fact about the repository — {@link
     * DefaultBranchDiscovery.Undetermined}, no budget spent — never a {@link
     * DefaultBranchDiscovery.Discovered} carrying it onward (NFR-S3, task 12.2).
     *
     * <p>This is the one place a {@link DefaultBranch} is built, which is what makes the type an
     * enforcement rather than a label: every later holder of the value received it from here
     * (FR4, FR10, M2, task 12.4). The rules are the ref-name grammar plus the refusal of {@code
     * HEAD} — a name git itself will not let a remote carry on a branch.
     */
    private static DefaultBranchDiscovery discovered(String branch) {
        return DefaultBranch.violation(branch)
                .<DefaultBranchDiscovery>map(violation ->
                        new DefaultBranchDiscovery.Undetermined(UntrustedText.factory("origin named a default"
                                + " branch '" + LogText.forLog(branch) + "' that is not a usable branch name: "
                                + violation)))
                .orElseGet(() -> new DefaultBranchDiscovery.Discovered(new DefaultBranch(branch)));
    }

    /**
     * Parses the short branch name out of {@code ls-remote --symref} output, whose first line is
     * {@code "ref: refs/heads/<name>\tHEAD"} followed by the tip line. An empty repository answers
     * successfully with no symref line at all, which is how {@link
     * DefaultBranchDiscovery.Undetermined} is reached.
     *
     * <p>Only {@code refs/heads/} is accepted: a {@code HEAD} pointing anywhere else is not a
     * branch this factory can branch from, and reporting it as one would carry a malformed name
     * into a refspec.
     *
     * @param stdout the command's standard output; never null
     * @return the branch name, or empty when the output names no head ref
     */
    static Optional<String> symrefBranch(String stdout) {
        return stdout.lines()
                .filter(line -> line.startsWith(SYMREF_HEAD))
                .map(line -> line.substring(SYMREF_HEAD.length()).split("\\s", 2)[0])
                .filter(branch -> !branch.isEmpty())
                .findFirst();
    }
}
