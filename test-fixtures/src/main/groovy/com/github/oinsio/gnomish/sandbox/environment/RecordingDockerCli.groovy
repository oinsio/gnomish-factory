package com.github.oinsio.gnomish.sandbox.environment

/**
 * A hand-rolled fake {@link DockerCli} for the container adapter's daemon-free
 * unit specs: Spock cannot mock a concrete class here (no byte-buddy on the
 * classpath), and the docker seam is a subprocess runner best faked directly —
 * the same reason {@code GitProcessRunner} is exercised against a real/fake
 * binary rather than mocked. It records every {@code run} and {@code start} argv
 * and answers {@code run} through a caller-supplied closure (default: exit 0),
 * so a spec scripts list output, failures, or a daemon outage per command and
 * then asserts which objects were created or removed.
 */
class RecordingDockerCli extends DockerCli {

    final List<List<String>> runs = []
    final List<List<String>> starts = []

    /** Maps a run argv to its result; may throw to simulate a runtime outage. Default: exit 0. */
    Closure<DockerResult> onRun = { List<String> args ->
        new DockerResult(0, '', '')
    }

    RecordingDockerCli() {
        super('docker')
    }

    @Override
    DockerResult run(List<String> args) {
        runs << args
        onRun.call(args)
    }

    @Override
    Process start(List<String> args, boolean mergeStderr) {
        starts << args
        throw new IllegalStateException('start() not expected in a daemon-free unit spec: ' + args)
    }
}
