package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR16 of harden-task-branch-contract: a ref enumeration that never ran to its own exit
 * established nothing about which task branches exist, so {@code list} must not answer with an
 * empty listing — the answer that tells an operator "no task branches" because their shutdown
 * signal landed mid-read. The same non-exit-is-not-a-fact rule the tip reads enforce
 * ({@link GitShowTipTerminationSpec}), applied to the enumeration that feeds them.
 */
class TaskBranchListerTerminationSpec extends Specification implements StallingReadGitFixture {

    @TempDir
    Path tempDir

    def "FR16: an interrupted ref enumeration is unavailability, never an empty listing"() {
        given:
        def lister = new TaskBranchLister(new GitProcessRunner(stallingGit(tempDir).toString()))
        def outcome = null
        Throwable thrown = null

        when:
        def reader = new Thread({
            try {
                outcome = lister.list(tempDir)
            } catch (Throwable t) {
                thrown = t
            }
        })
        reader.start()
        awaitReadStarted(tempDir)
        reader.interrupt()
        reader.join(Duration.ofSeconds(30).toMillis())

        then:
        thrown instanceof BranchTipUnavailableException
        outcome == null
    }
}
