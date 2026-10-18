package com.github.oinsio.gnomish.sandbox

import java.nio.file.Path
import spock.lang.Specification

/**
 * StderrPolicy: each arm sets the builder's stderr disposition whatever it held before (design
 * D18 of fix-terminal-receipt-convergence).
 *
 * FR20 of fix-terminal-receipt-convergence: the round's stderr goes to a file, never to a pipe
 * nothing reads.
 */
class StderrPolicySpec extends Specification {

    // FR20: a command check reads one chronological stream
    def "Merged folds stderr into the output stream"() {
        given:
        def builder = new ProcessBuilder('true').redirectError(new File('/tmp/elsewhere'))

        when:
        StderrPolicy.MERGED.applyTo(builder)

        then:
        builder.redirectErrorStream()
    }

    // FR20: the file channel drains its own second pipe
    def "Piped leaves stderr as a pipe of its own, whatever the builder held"() {
        given:
        def builder = new ProcessBuilder('true').redirectErrorStream(true).redirectError(new File('/tmp/elsewhere'))

        when:
        StderrPolicy.PIPED.applyTo(builder)

        then:
        !builder.redirectErrorStream()
        builder.redirectError() == ProcessBuilder.Redirect.PIPE
    }

    // FR20: the agent round's stderr is a host file
    def "ToFile writes stderr to the named file, unmerged"() {
        given:
        def file = Path.of('/tmp/round/stderr')
        def builder = new ProcessBuilder('true').redirectErrorStream(true)

        when:
        StderrPolicy.toFile(file).applyTo(builder)

        then:
        !builder.redirectErrorStream()
        builder.redirectError().type() == ProcessBuilder.Redirect.Type.WRITE
        builder.redirectError().file() == file.toFile()
    }

    def "ToFile refuses a missing file"() {
        when:
        StderrPolicy.toFile(null)

        then:
        thrown(NullPointerException)
    }

    def "the arms compare by value"() {
        expect:
        StderrPolicy.toFile(Path.of('/a')) == new StderrPolicy.ToFile(Path.of('/a'))
        StderrPolicy.MERGED == new StderrPolicy.Merged()
        StderrPolicy.PIPED != StderrPolicy.MERGED
    }
}
