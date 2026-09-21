package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR6 of harden-task-branch-contract: a tip read that never ran to its own exit established
 * nothing about the tip, so it must not answer "the tip does not carry this file" — the answer that
 * classifies a live branch as {@code Bare} and routes the take to a fresh claim, forking a second
 * branch for a task that already has one. The tip-reader seam is where the invocation outcome is
 * classified (design D3, D14), so the unavailability leaves the source before any fact is assembled.
 *
 * <p>Driven through the runner's git-binary seam: a stand-in {@code git} that stalls on every read
 * the seam makes, interrupted mid-wait — the outcome no real repository can be asked to produce.
 */
class GitShowTipTerminationSpec extends Specification implements StallingReadGitFixture {

    @TempDir
    Path tempDir

    private Throwable interruptDuring(Closure<?> read) {
        Throwable thrown = null
        def reader = new Thread({
            try {
                read.call()
            } catch (Throwable t) {
                thrown = t
            }
        })
        reader.start()
        awaitReadStarted(tempDir)
        reader.interrupt()
        reader.join(Duration.ofSeconds(30).toMillis())
        return thrown
    }

    private RefTipSource source() {
        new RefTipSource(new GitProcessRunner(stallingGit(tempDir).toString()), tempDir, 'gnomish/PROJ-1')
    }

    /** The seam itself, for the two answers {@link RefTipSource} does not expose. */
    private GitShowTip tip() {
        new GitShowTip(new GitProcessRunner(stallingGit(tempDir).toString()), tempDir, 'gnomish/PROJ-1')
    }

    def "FR6: an interrupted file read is unavailability, never an absent file"() {
        when:
        def thrown = interruptDuring {
            source().readAtTip('.gnomish-task/task.json')
        }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    def "FR1: an interrupted state-file read is unavailability, never an absent state file"() {
        when:
        def thrown = interruptDuring {
            source().readAtTip('.gnomish-task/state.json')
        }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    def "FR1: an interrupted history search is unavailability, never an undelivered branch"() {
        when:
        def thrown = interruptDuring { source().cleanupCommitInHistory() }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    // NFR-R2, FR4 of fix-envelope-medium: the tree-entry predicate answers a cut-off `cat-file`
    //     with unavailability, never with false — a false here tells the cleanup guard the envelope
    //     is already gone and the salvage restore that there is nothing to restore.
    def "NFR-R2: an interrupted tree-entry test is unavailability, never an absent path"() {
        when:
        def thrown = interruptDuring { tip().carries('.gnomish-task') }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    // NFR-R2, FR6 of fix-envelope-medium: locating the cleanup commit is the same history search,
    //     so a cut-off walk is unavailability rather than "this branch was never cleaned up".
    def "NFR-R2: an interrupted cleanup-commit lookup is unavailability, never an empty result"() {
        when:
        def thrown = interruptDuring { tip().cleanupCommit() }

        then:
        thrown instanceof BranchTipUnavailableException
    }
}
