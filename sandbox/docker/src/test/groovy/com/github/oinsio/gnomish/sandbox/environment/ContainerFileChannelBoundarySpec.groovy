package com.github.oinsio.gnomish.sandbox.environment

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
        new ContainerFileChannel(docker, 'k1', '/gnomish/work', '/gnomish/scratch')
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
        docker.process.exit = 5

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
        docker.process.exit = 1
        docker.process.stderrStream = new ByteArrayInputStream('cat: no space left on device\n'.bytes)

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
        docker.process.exit = 2
        docker.process.stderrStream = new ByteArrayInputStream('head: permission denied'.bytes)

        when:
        channel(docker).readFile('note.txt', 10)

        then:
        def e = thrown(UncheckedIOException)
        e.message.contains('stderr: head: permission denied')
    }
}
