package com.github.oinsio.gnomish.adapter.agent

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * {@link RoundScratchDirectory.Writer} (FR5 of define-executor-contract, design D3: a {@code program}
 * run keeps its stderr "as the agent round keeps it"): the writer names the family the tail is
 * minted in and the noun its lines use — the agent CLI's as agent text, a program's as subprocess
 * output.
 */
class RoundScratchDirectoryWriterSpec extends Specification {

    @TempDir
    Path parent

    LogCaptureSupport logs

    def cleanup() {
        logs?.detach()
    }

    private static Path stderrFile(RoundScratchDirectory scratch) {
        (scratch.stderr() as StderrPolicy.ToFile).file()
    }

    // FR5: the tail is minted in the writer's family, and an unwritten stderr is an empty text of it
    def "FR5: a #writer stderr tail is #family text"() {
        given:
        def scratch = RoundScratchDirectory.open(parent, writer)

        when:
        def empty = scratch.stderrTail()
        Files.writeString(stderrFile(scratch), 'error: no configuration\n')
        def tail = scratch.stderrTail()

        then:
        empty == mint('')
        tail == mint('error: no configuration\n')

        cleanup:
        scratch.close()

        where:
        writer | family | mint
        RoundScratchDirectory.Writer.AGENT_CLI | 'agent' | { String t ->
            UntrustedText.agent(t)
        }
        RoundScratchDirectory.Writer.PROGRAM | 'subprocess' | { String t ->
            UntrustedText.subprocess(t)
        }
    }

    // FR5: a successful program run's tail is one DEBUG line naming the program executor
    def "FR5: a program run's tail line names the program executor, not the agent CLI"() {
        given:
        def scratch = RoundScratchDirectory.open(parent, RoundScratchDirectory.Writer.PROGRAM)
        Files.writeString(stderrFile(scratch), 'warming up')
        logs = LogCaptureSupport.attach(RoundScratchDirectory, Level.DEBUG)

        when:
        scratch.logTailOnSuccess()

        then:
        logs.list*.formattedMessage == [
            'program executor stderr of the round, last 10240 bytes at most: warming up'
        ]

        cleanup:
        scratch.close()
    }

    // FR5: an unreadable stderr is a factory sentence naming whose stderr it was
    def "FR5: a failure's tail of an unreadable program stderr names the program executor"() {
        given:
        def scratch = RoundScratchDirectory.open(parent, RoundScratchDirectory.Writer.PROGRAM)
        Files.createDirectory(stderrFile(scratch))

        expect:
        scratch.tailForFailure().contains("the program executor's stderr in ")

        cleanup:
        scratch.close()
    }
}
