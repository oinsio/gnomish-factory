package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reusable Spock fixture: a stand-in {@code git} binary that answers every invocation by
 * delegating to the real one, except for a single named subcommand, which exits non-zero with
 * an empty stdout and a {@code fatal:} line on stderr.
 *
 * <p>This is the shape the exit-code verification rule (FR13 of harden-logging-observability)
 * needs and no real repository can be asked to produce on demand: a git probe that <em>ran</em>
 * and <em>failed</em>, so its empty output stream would read as a fact — an untouched state
 * directory, an unmoved tip, an empty branch listing — unless the caller checks the exit code.
 *
 * <p>The stand-in is the committed preset {@code fail-subcommand}, reached through a per-run link
 * named after the subcommand it fails; healing re-points that link at {@code delegate-git} (ADR
 * 0015). Sibling of
 * {@link StallingReadGitFixture}, which drives the other half of the same rule: a probe that
 * never ran to its own exit at all.
 */
trait FailingSubcommandGitFixture {

    /** The stderr the stand-in emits, and the evidence a caller is expected to carry through. */
    static final String GIT_FAILURE_STDERR = StandIn.data('stderr#bad-revision').trim()

    /** Ends the outage: from here on the stand-in delegates {@code subcommand} to the real git. */
    void healGit(Path dir, String subcommand) {
        StandIn.repoint(failingLink(dir, subcommand), 'delegate-git')
    }

    /**
     * The stand-in {@code git} of {@code dir} — one per subcommand — that fails only on {@code subcommand}, and only until
     * {@link #healGit} is called for it.
     *
     * @param dir where the stand-in's per-run link is created
     * @param subcommand the git subcommand to fail, matched after any leading {@code -c} pairs
     * @return the stand-in binary's path, for {@code new GitProcessRunner(path.toString())}
     */
    Path gitFailingOn(Path dir, String subcommand) {
        Path link = failingLink(dir, subcommand)
        Files.isSymbolicLink(link) ? link : StandIn.link(link, 'fail-subcommand')
    }

    private static Path failingLink(Path dir, String subcommand) {
        Files.createDirectories(dir.resolve('failing-git')).resolve(subcommand)
    }
}
