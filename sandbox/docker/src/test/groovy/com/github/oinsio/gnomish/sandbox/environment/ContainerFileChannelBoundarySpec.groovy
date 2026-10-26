package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.subprocess.StdinSource
import spock.lang.Specification

/**
 * NFR-S3 of add-sandbox-core: {@link ContainerFileChannel} boundary and
 * path-handling behaviour not covered by the streaming-mechanics spec — the
 * {@code sizeCap} precondition boundary, the {@code head -c} bound really being
 * {@code sizeCap + 1} (never {@code sizeCap - 1}), {@code validate} handing back
 * the real resolved path (never blank), and a plain (non-interrupted) nonzero
 * exit from the in-box write really propagating (never silently 0).
 */
class ContainerFileChannelBoundarySpec extends Specification {

    private static ContainerFileChannel channel(DockerCli docker) {
        new ContainerFileChannel(docker, new ContainerExecLaunches('k1', '/gnomish/work', [] as Set), '/gnomish/work', '/gnomish/scratch')
    }

    // FR1, NFR-S3: the channel's content travels through the exec — handed to the launch owner as the
    //     client's input (D20 of fix-terminal-receipt-convergence), read from its stdout — and no
    //     docker call is a copy or an archive transfer
    // Scenario: sandbox/execution-environment / Container file content streams through exec
    def "putFile and readFile stream the content through docker exec, never docker cp or an archive"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()
        def payload = 'factory bytes'.getBytes('UTF-8')
        docker.script.stdout = 'box bytes'.getBytes('UTF-8')

        when:
        channel(docker).putFile('note.txt', payload)
        def read = channel(docker).readFile('note.txt', 64)

        then: 'the written bytes became the exec\'s input, the read bytes came from its stdout'
        docker.fed(0) == payload
        docker.sources[1].is(StdinSource.NONE)
        new String(read.get(), 'UTF-8') == 'box bytes'

        and: 'both calls were docker exec, and no argv names cp, an archive, or a host path'
        docker.starts.size() == 2
        docker.starts.every { it.first() == 'exec' }
        docker.starts.every { argv ->
            !argv.any {
                it in ['cp', 'tar', 'export', 'save']
            }
        }
    }

    def "readFile rejects a non-positive sizeCap before ever touching docker"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()

        when:
        channel(docker).readFile('note.txt', cap)

        then:
        thrown(IllegalArgumentException)
        docker.starts.isEmpty()

        where:
        cap << [-1L, 0L]
    }

    // Line 76: bound = sizeCap + 1 — mutated to a subtraction, "head -c 40" would be requested
    // instead of "head -c 42" for a cap of 41, silently under-reading real files near the cap.
    def "readFile requests exactly sizeCap + 1 bytes from the in-box read — the bound is added, not subtracted"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()

        when:
        channel(docker).readFile('note.txt', 41)

        then:
        docker.starts.last().last() == '42'
    }

    def "validate hands back the real resolved path — relative paths anchor on the working copy"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()

        when:
        channel(docker).readFile('sub/note.txt', 10)

        then:
        def argv = docker.starts.last()
        argv[argv.indexOf('gnomish') + 1] == '/gnomish/work/sub/note.txt'
    }

    def "validate hands back the real resolved path — an absolute in-box path passes through"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()

        when:
        channel(docker).readFile('/gnomish/scratch/out.json', 10)

        then:
        def argv = docker.starts.last()
        argv[argv.indexOf('gnomish') + 1] == '/gnomish/scratch/out.json'
    }

    // ContainerFileChannel#completed: a plain nonzero exit (no interrupt in play) must still
    // surface as the failure, never coerced to 0 — and with nothing on stderr the message carries
    // no empty stderr suffix.
    def "a plain nonzero exit from the in-box write is propagated, never coerced to 0"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()
        docker.script.exit = 5

        when:
        channel(docker).putFile('note.txt', 'x'.getBytes())

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('exit 5')
        !e.message.contains('stderr')
    }

    // NFR-O1 of bound-subprocess-commands: the failure carries what the in-box command said on
    // stderr — "exit 1" alone would send an operator back into the box to re-run it by hand.
    def "a failing in-box write carries the command's stderr in the failure message"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()
        docker.script.exit = 1
        docker.script.stderr = 'cat: no space left on device\n'

        when:
        channel(docker).putFile('note.txt', 'x'.getBytes())

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('exit 1')
        e.message.contains('stderr: cat: no space left on device')
    }

    // NFR-O1: the same on the read path
    def "a failing in-box read carries the command's stderr in the failure message"() {
        given:
        def docker = new ScriptedFileChannelDockerCli()
        docker.script.exit = 2
        docker.script.stderr = 'head: permission denied'

        when:
        channel(docker).readFile('note.txt', 10)

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('stderr: head: permission denied')
    }
}
