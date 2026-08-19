package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.contract.PortContractSupport
import com.github.oinsio.gnomish.sandbox.DenialCursor
import com.github.oinsio.gnomish.sandbox.ExecCommand
import com.github.oinsio.gnomish.sandbox.ExecHandle
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * The shared port-level contract for {@link TaskExecutionEnvironment} (task 1.5,
 * M1): the same suite both the host adapter (this change) and the container
 * adapter (a later task group) pass. A concrete subclass binds a
 * <em>materialized</em> environment over a real working copy through {@link
 * #arrange}, and the rows drive the port's channel — exec (with stdin, merged
 * and separate streams), putFile/readFile round-trip with a size cap, harvest,
 * idempotent dispose, and the passport — using only streams and factory-chosen
 * paths, never adapter-specific calls.
 *
 * <p>Commands are POSIX {@code sh -c} scripts, the one assumption both adapters
 * share (the container image ships a shell). Pin-at-commit materialization is
 * exercised by the git-mechanics task group, once host materialize checks out a
 * pinned tree; this suite covers the behavior-neutral host surface.
 *
 * <p>Implements FR1, FR4, NFR-S3, M1 of add-sandbox-core.
 */
abstract class TaskExecutionEnvironmentContract extends Specification implements PortContractSupport {

    protected TaskExecutionEnvironment env

    /**
     * Build and materialize the environment-under-test over a real working copy,
     * or return empty to declare the port unproducible for this adapter.
     */
    protected abstract Optional<TaskExecutionEnvironment> arrange()

    /** The port name used in recorded port-shape findings. */
    protected abstract String portName()

    protected TaskExecutionEnvironment materialized() {
        def arranged = arrange()
        assumeProducible(arranged, portName(), 'materialized environment')
        env = arranged.get()
        env
    }

    def cleanup() {
        env?.dispose()
    }

    private static String readFully(InputStream stream) {
        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
    }

    private static ExecCommand shell(String script, String stdin = null, boolean mergeStderr = false) {
        new ExecCommand(['sh', '-c', script], [:], stdin, mergeStderr)
    }

    // FR4: a command runs through exec() and its output and exit code reach the caller
    def "exec runs a command, streaming output and an exit code"() {
        given: 'a materialized environment'
        def e = materialized()

        when: 'a simple command runs'
        def handle = e.exec(shell('echo hello'))
        def out = readFully(handle.output())
        def code = handle.waitForExit()

        then: 'the output and a zero exit code come back'
        out.trim() == 'hello'
        code == 0
    }

    // FR4: a non-zero exit code is reported faithfully
    def "exec reports a non-zero exit code"() {
        given: 'a materialized environment'
        def e = materialized()

        expect: 'the command exit code is returned unchanged'
        e.exec(shell('exit 3')).waitForExit() == 3
    }

    // FR24, D18: stdin content is delivered to the process. Bounded on purpose: when stdin is not
    // delivered (e.g. the pump call is dropped) `cat` never sees EOF and hangs, so the wait must
    // time out and fail as a red assertion within seconds rather than blocking forever.
    def "exec delivers stdin to the process"() {
        given: 'a materialized environment'
        def e = materialized()

        when: 'a command reads stdin under a bounded wait'
        // 12s: generous enough that a real cat echo never trips it even under heavy PIT-coverage
        // load, yet well inside PIT's per-mutation budget so the drop-the-pump mutant — which hangs
        // cat forever — dies as a red Exited assertion within the budget rather than as a TIMED_OUT.
        def handle = e.exec(shell('cat', 'piped-prompt-content'))
        def wait = handle.waitForExitOrTimeout(Duration.ofSeconds(12), {
            -> Instant.now()
        })
        def out = readFully(handle.output())

        then: 'cat saw EOF (stdin was delivered and closed), exited, and echoed the bytes back'
        wait instanceof ExecHandle.Wait.Exited
        out.contains('piped-prompt-content')
    }

    // FR1: mergeStderr folds stderr into the one output stream
    def "exec merges stderr into stdout when asked"() {
        given: 'a materialized environment'
        def e = materialized()

        when: 'a command writes to both streams with merge on'
        def handle = e.exec(shell('echo out; echo err 1>&2', null, true))
        def out = readFully(handle.output())
        handle.waitForExit()

        then: 'both lines appear on the merged stream'
        out.contains('out')
        out.contains('err')
    }

    // FR1, NFR-S3: a factory-authored file round-trips through the scratch channel
    def "putFile then readFile round-trips bytes through scratch"() {
        given: 'a materialized environment and a scratch path'
        def e = materialized()
        def path = e.scratchRoot() + '/probe.json'
        def bytes = 'findings-bytes'.getBytes(StandardCharsets.UTF_8)

        when: 'the factory writes then reads it back'
        e.putFile(path, bytes)
        def read = e.readFile(path, 1024)

        then: 'the exact bytes come back'
        read.isPresent()
        read.get() == bytes
    }

    // NFR-S3: an absent channel file reads as empty, never a fabricated value
    def "readFile of an absent path is empty"() {
        given: 'a materialized environment'
        def e = materialized()

        expect: 'reading a nonexistent scratch file is empty'
        e.readFile(e.scratchRoot() + '/absent', 1024).isEmpty()
    }

    // FR17: model-output write confinement — the file channel refuses every .git/** path, so
    //     no channel write can plant a hook or rewrite repository internals (the contract test
    //     of task 8.5 of add-sandbox-core)
    def "putFile refuses a #label path"() {
        given: 'a materialized environment'
        def e = materialized()

        when:
        e.putFile(path, 'planted'.getBytes(StandardCharsets.UTF_8))

        then:
        thrown(PathEscapeException)

        where:
        label | path
        'hook' | '.git/hooks/post-checkout'
        'git config' | '.git/config'
        'traversal' | '../../outside.txt'
    }

    // FR17, NFR-S3: reads are confined exactly like writes
    def "readFile refuses a .git path"() {
        given: 'a materialized environment'
        def e = materialized()

        when:
        e.readFile('.git/config', 1024)

        then:
        thrown(PathEscapeException)
    }

    // NFR-S3: an oversized file is capped at the read cap
    def "readFile caps the returned content"() {
        given: 'a materialized environment holding a 100-byte file'
        def e = materialized()
        def path = e.scratchRoot() + '/big'
        e.putFile(path, ('x' * 100).getBytes(StandardCharsets.UTF_8))

        expect: 'the read returns at most the cap'
        e.readFile(path, 10).get().length == 10
    }

    // FR5: harvest completes without error (host: a no-op)
    def "harvest completes"() {
        given: 'a materialized environment'
        def e = materialized()

        when: 'harvest runs'
        e.harvest()

        then: 'no error'
        noExceptionThrown()
    }

    // NFR-R2: dispose is idempotent
    def "dispose is idempotent"() {
        given: 'a materialized environment'
        def e = materialized()

        when: 'dispose runs twice'
        e.dispose()
        e.dispose()

        then: 'the second call succeeds'
        noExceptionThrown()
    }

    // FR14: the adapter exposes a passport
    def "passport is present"() {
        given: 'a materialized environment'
        def e = materialized()

        expect: 'a non-null passport'
        e.passport() != null
    }

    // FR1, NFR-R1 of fix-denial-report-attachment: denials are readable through the port itself,
    //     and an environment with no egress guard answers empty rather than refusing the question
    def "denialFindings answers through the port, empty for a guard-less environment"() {
        given: 'a materialized environment that made no denied request'
        def e = materialized()

        expect: 'a truthful empty answer, never null and never a failure'
        e.denialFindings() == []
    }

    // FR5 of fix-denial-report-attachment: the cursor is offered, never imposed — an environment
    //     with no denial source has no position to hand back and accepts an offer as a no-op
    def "denialCursor and restoreDenialCursor answer through the port, whatever the denial source"() {
        given: 'a materialized environment that has read no denials'
        def e = materialized()

        expect: 'no position to commit yet'
        e.denialCursor().isEmpty()

        when: 'a cursor from an unrelated source is offered'
        e.restoreDenialCursor(new DenialCursor('some-other-source', '2026-08-19T10:00:00Z'))

        then: 'the offer is accepted without failing, and denials still read truthfully'
        noExceptionThrown()
        e.denialFindings() == []
    }

    // FR1, FR6: waitForExitOrTimeout returns Exited for a fast command
    def "waitForExitOrTimeout returns Exited within budget"() {
        given: 'a materialized environment'
        def e = materialized()

        when: 'a fast command runs under a generous timeout'
        def handle = e.exec(shell('true'))
        def wait = handle.waitForExitOrTimeout(Duration.ofSeconds(30), {
            -> Instant.now()
        })

        then: 'it exited naturally'
        wait instanceof ExecHandle.Wait.Exited
    }
}
