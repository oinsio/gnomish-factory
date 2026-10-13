package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.GitTransfer
import com.github.oinsio.gnomish.gittransfer.Refspec
import com.github.oinsio.gnomish.gittransfer.TransferSource
import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR4, G3 of bound-subprocess-commands: git's own stall detection is the primary mechanism for a
 * dead connection, and the network deadline stays a hard wall-clock bound that progress does not
 * extend — a transfer that needs longer is given it by raising {@code factory.git-network-timeout}.
 * The stand-in prints a progress line every half second for two seconds, then succeeds.
 */
class GitProcessRunnerProgressingTransferSpec extends Specification {

    @TempDir
    Path tempDir

    // FR4, G3 of bound-subprocess-commands.
    // F72: scenario text aligned to what this test asserts
    // Scenario: git/git-task-persistence / A slow but progressing transfer completes
    def "FR4: a progressing fetch finishes within a deadline that covers it, and is timed out by one that does not"() {
        given:
        def runner = new GitProcessRunner(StandIn.git('progressing-fetch').toString(), deadline)

        when:
        def result = runner.run(tempDir, GitTransfer.fetch(TransferSource.ORIGIN, new Refspec('refs/heads/main:refs/remotes/origin/main')))

        then:
        result.termination() == termination

        where: 'progress does not extend the deadline; the operator raises it for a longer transfer'
        deadline | termination
        Duration.ofMillis(500) | Termination.TIMED_OUT
        Duration.ofSeconds(30) | Termination.EXITED
    }
}
