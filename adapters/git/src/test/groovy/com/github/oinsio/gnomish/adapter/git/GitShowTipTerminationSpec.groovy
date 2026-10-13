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
 * <p>Driven through the runner's port ({@link StalledReadGit}, design D27 of
 * supervise-daemon-loops-and-embed-dashboard): a scripted git that blocks on every read the seam
 * makes until interrupted — the outcome no real repository can be asked to produce.
 */
class GitShowTipTerminationSpec extends Specification implements StalledReadGit {

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
        awaitReadStarted(tempDir, reader)
        reader.interrupt()
        reader.join(Duration.ofSeconds(30).toMillis())
        return thrown
    }

    private RefTipSource source() {
        RefTipSource.at(stallingGit(tempDir), tempDir, 'PROJ-1', 'gnomish/PROJ-1')
    }

    /** The seam itself, at a registered commit, for the reads past the register lookup. */
    private GitShowTip registered() {
        new GitShowTip(stallingGit(tempDir), tempDir, new RegisteredCommit('a' * 40))
    }

    // Scenario: git/git-task-persistence / Absence and unavailability are distinct answers
    def "FR6: an interrupted file read is unavailability, never an absent file"() {
        when:
        def thrown = interruptDuring {
            registered().readAtRegister('.gnomish-task/task.json')
        }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    // FR13 of tighten-spec-contract: the register lookup that makes a source is a read too, so a
    //     cut-off one is unavailability — never "no register", which would quarantine a live task.
    def "FR1: an interrupted register lookup is unavailability, never a missing register"() {
        when:
        def thrown = interruptDuring { source() }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    def "FR1: an interrupted cleanup-subject read is unavailability, never an undelivered branch"() {
        when:
        def thrown = interruptDuring { registered().isCleanupCommit() }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    // NFR-R2, FR4 of fix-envelope-medium: the writer base's tree-entry predicate answers a cut-off
    //     `cat-file` with unavailability, never with false — a false here tells the cleanup guard
    //     the envelope is already gone and the salvage restore that there is nothing to restore.
    def "NFR-R2: an interrupted tree-entry test is unavailability, never an absent path"() {
        when:
        def thrown = interruptDuring {
            RegisterBase.carries(stallingGit(tempDir), tempDir, 'PROJ-1', '.gnomish-task')
        }

        then:
        thrown instanceof BranchTipUnavailableException
    }

    // NFR-R2: the writer base's file read is gated the same way.
    def "NFR-R2: an interrupted base read is unavailability, never a refusal git gave"() {
        when:
        def thrown = interruptDuring {
            RegisterBase.show(stallingGit(tempDir), tempDir, 'PROJ-1', '.gnomish-task/task.json')
        }

        then:
        thrown instanceof BranchTipUnavailableException
    }
}
