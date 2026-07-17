package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, D6 of add-manual-run: the process runner spawns {@code sh -c <command>} with the
 * workspace as cwd and inherited environment, merges stdout/stderr into one chronological
 * stream, retains a bounded tail, and captures the exit code.
 */
class CommandProcessRunnerSpec extends Specification {

    @TempDir
    Path tempDir

    def runner = new CommandProcessRunner('sh')

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(tempDir)
    }

    private static VerifyCheck.Command command(String line) {
        new VerifyCheck.Command(line)
    }

    def "command runs with cwd set to the workspace root"() {
        given:
        def check = command('pwd')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        outcome.outputTail().trim() == tempDir.toRealPath().toString()
    }

    def "environment is inherited from the parent process"() {
        given: 'PATH is set in essentially every process environment'
        def check = command('echo $PATH')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        !outcome.outputTail().trim().isEmpty()
    }

    def "stdout and stderr merge into one chronological stream"() {
        given:
        def check = command('echo out1; echo err1 1>&2; echo out2; echo err2 1>&2')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        outcome.outputTail().readLines() == [
            'out1',
            'err1',
            'out2',
            'err2'
        ]
    }

    def "exit code is captured correctly for a zero exit"() {
        given:
        def check = command('exit 0')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
    }

    def "exit code is captured correctly for a non-zero exit"() {
        given:
        def check = command('exit 3')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 3
    }

    def "output is bounded to roughly 200 lines when a command produces more"() {
        given: 'a command producing 500 short lines'
        def check = command('for i in $(seq 1 500); do echo "line-$i"; done')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        def lines = outcome.outputTail().readLines()
        lines.size() <= 200
        lines.size() > 0
        // Tail must be the END of the stream, chronologically.
        lines.last() == 'line-500'
    }

    def "exactly 200 short lines are kept in full - the line-count bound does not evict at the boundary"() {
        given: 'a command producing exactly 200 short lines'
        def check = command('for i in $(seq 1 200); do echo "line-$i"; done')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        def lines = outcome.outputTail().readLines()
        lines.size() == 200
        lines.first() == 'line-1'
        lines.last() == 'line-200'
    }

    def "201 short lines evict exactly the oldest one - the line-count bound is a strict greater-than"() {
        given: 'a command producing exactly 201 short lines, one over the line-count bound'
        def check = command('for i in $(seq 1 201); do echo "line-$i"; done')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        def lines = outcome.outputTail().readLines()
        lines.size() == 200
        // The oldest line (line-1) is evicted; the tail starts at line-2.
        lines.first() == 'line-2'
        lines.last() == 'line-201'
    }

    def "output is bounded to roughly 10 KB when a command produces long lines"() {
        given: 'a command producing far more than 10 KB of output on a handful of long lines'
        def check = command('for i in $(seq 1 20); do printf "%01000d\\n" 0; done')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        outcome.outputTail().length() <= 10 * 1024
    }

    def "a single line at exactly the byte cap is kept whole, not evicted"() {
        given: 'one line of exactly 10 KB (MAX_TAIL_BYTES), each byte counted plus the newline'
        // 10 * 1024 - 1 body bytes + 1 accounted newline byte == exactly MAX_TAIL_BYTES.
        def check = command('printf "%*s" 10239 "" | tr " " "a"; printf "\\n"')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        def lines = outcome.outputTail().readLines()
        lines.size() == 1
        lines[0].length() == 10239
    }

    def "a single line one byte over the cap is evicted entirely, leaving an empty tail"() {
        given: 'one line whose accounted length (body + 1 newline byte) is exactly one over MAX_TAIL_BYTES'
        // 10240 body bytes + 1 accounted newline byte == MAX_TAIL_BYTES + 1: strictly over the
        // cap, and the ONLY line, so eviction empties the deque. Kills a mutant that replaces the
        // "+ 1" newline-byte accounting on the added line with "- 1" (which would keep this
        // exactly AT the cap, not over it, and wrongly keep the line) — the two byte counts here
        // differ from the previous (at-the-cap) test by exactly the 1 byte that mutant would swallow.
        def check = command('printf "%*s" 10240 "" | tr " " "a"; printf "\\n"')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        outcome.outputTail() == ''
    }

    def "a second line pushing the running total one byte over the cap evicts the first line entirely"() {
        given: 'a first line that alone sits exactly at the byte cap, followed by a second short line'
        // First line accounted at exactly MAX_TAIL_BYTES (10239 body + 1 newline); any further
        // line pushes the running total over the bound and must evict the first line, proving
        // the comparison is a strict greater-than, not greater-or-equal, and the running byte
        // total is decremented (not incremented) when a line is evicted.
        def check = command('printf "%*s" 10239 "" | tr " " "a"; printf "\\n"; echo second')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        def lines = outcome.outputTail().readLines()
        lines == ['second']
    }

    def "eviction byte-accounting stays exact across hundreds of evictions - the retained line count is correct"() {
        given: '400 lines of exactly 99 body bytes each (100 accounted with the newline), far past the 10 KB cap'
        // 10 * 1024 bytes / 100 accounted-bytes-per-line == 102 lines fit under the byte cap. A mutant
        // that flips the eviction-side "+ 1" newline accounting (bytes -= evicted.length + 1) to "- 1"
        // under-subtracts by two bytes on every eviction, so its running total drifts upward and it
        // evicts too aggressively, keeping fewer than 102 lines. A single eviction cannot reveal this
        // (the total lands far below the cap either way, as the existing single-/double-line specs
        // show) — only the drift compounded over hundreds of evictions changes the retained count.
        def check = command('for i in $(seq 1 400); do printf "%099d\\n" $i; done')

        when:
        def outcome = runner.run(check, workspace())

        then:
        outcome.exitCode() == 0
        def lines = outcome.outputTail().readLines()
        lines.size() == 102
        lines.last() == String.format('%099d', 400)
    }

    def "an interrupt raised while waiting for the process maps to the interrupted exit code"() {
        given: 'a Process whose waitFor() throws InterruptedException, exercised directly (no blocking read)'
        def interruptingProcess = new Process() {
                    OutputStream getOutputStream() {
                        OutputStream.nullOutputStream()
                    }
                    InputStream getInputStream() {
                        InputStream.nullInputStream()
                    }
                    InputStream getErrorStream() {
                        InputStream.nullInputStream()
                    }
                    int waitFor() throws InterruptedException {
                        throw new InterruptedException()
                    }
                    int exitValue() {
                        throw new IllegalThreadStateException()
                    }
                    void destroy() {}
                }
        def method = CommandProcessRunner.getDeclaredMethod('waitFor', Process)
        method.setAccessible(true)

        when:
        def result = method.invoke(null, [interruptingProcess] as Object[])

        then: 'the catch returns -1 and re-raises the thread interrupt flag (which we consume to isolate this test)'
        result == -1
        Thread.interrupted()

        cleanup:
        Thread.interrupted()
    }
}
