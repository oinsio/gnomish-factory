package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.gittransfer.GitVersion
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.subprocess.StdinSource

/**
 * A hand-rolled fake {@link DockerCli} for the container adapter's daemon-free
 * unit specs: Spock cannot mock a concrete class here (no byte-buddy on the
 * classpath), and the docker seam is a subprocess runner best faked directly —
 * the same reason {@code GitProcessRunner} is exercised against a real/fake
 * binary rather than mocked. It records every {@code run} argv and every {@code start}
 * launch — whole in {@link #launches} (argv, client environment, withheld names; FR3 of
 * fix-docker-exec-env-argv), and its argv alone in {@link #starts} for the specs that read only
 * the command line — and answers {@code run} through a caller-supplied closure (default: exit 0),
 * so a spec scripts list output, failures, or a daemon outage per command and
 * then asserts which objects were created or removed.
 */
class RecordingDockerCli extends DockerCli {

    final List<List<String>> runs = []
    final List<List<String>> starts = []
    final List<DockerExecLaunch> launches = []

    /** Maps a run argv to its result; may throw to simulate a runtime outage. Default: exit 0. */
    Closure<DockerResult> onRun = { List<String> args ->
        DockerResult.of(0, '', '')
    }

    /** A {@code git --version} line at the factory's one floor: what the base image's git prints. */
    static final String AT_FLOOR = "git version ${GitVersion.FLOOR}".toString()

    /**
     * What the sandbox image's {@code git --version} prints when the box's git floor is probed
     * before a seed clone (design D13 of tighten-spec-contract, as amended 2026-10-13): a git at the
     * factory's one floor by default ({@link GitVersion#FLOOR}, design D9 of add-sandbox-base-image),
     * so a scenario that is not about the floor never meets it, whatever the floor; a scenario that
     * is sets an older line, or {@code null} to hand the probe to {@link #onRun}.
     */
    String boxGitVersion = AT_FLOOR

    RecordingDockerCli() {
        super('docker')
    }

    /**
     * The one place a daemon-free fake answers the box's git-version probe: {@code run … git
     * --version} — the probe is the only {@code run} whose argv ends that way.
     *
     * @return the scripted result, or {@code null} when this argv is not the probe
     */
    static DockerResult answerBoxGitVersion(List<String> args, String line = AT_FLOOR) {
        args[0] == 'run' && args.takeRight(2) == ['git', '--version'] ? DockerResult.of(0, line + '\n', '') : null
    }

    /**
     * The one place a daemon-free fake answers the image inspection every fresh materialize and
     * every guard start issues (design D3 of add-sandbox-base-image; FR1 of
     * fix-image-declared-volumes) — the runtime's own answer for an image declaring no volume by
     * default, matched against the production argv itself rather than a copy of its shape, so a
     * change to {@code DockerCommands.inspectImageConfig} cannot leave a fake silently
     * non-matching. The answered image carries the contract label of the factory under test
     * ({@link ImageContract#LABEL} at {@code ImageContract.current()}), read from the contract
     * itself, so every daemon-free box passes the image-contract check (FR5 of
     * add-sandbox-base-image); a spec of the refusal answers the inspect itself.
     *
     * @param args the argv the fake was handed
     * @param answer the {@code .Config.Volumes} JSON the answered {@code Config} carries;
     *     {@code null} — the runtime's spelling of "this image declares none" — by default
     * @return the scripted result, or {@code null} when this argv is not the inspect, so the
     *     caller's own closure decides every other command
     */
    static DockerResult answerDeclaredVolumes(List<String> args, String answer = 'null') {
        args == DockerCommands.inspectImageConfig(args.last())
                ? DockerResult.of(0, '{"Volumes":' + answer + ',"Labels":{"' + ImageContract.LABEL + '":"' +
                ImageContract.current().contractVersion() + '"}}', '') : null
    }

    @Override
    DockerResult run(List<String> args) {
        runs << args
        def probe = boxGitVersion == null ? null : answerBoxGitVersion(args, boxGitVersion)
        probe != null ? probe : onRun.call(args)
    }

    @Override
    Process start(DockerExecLaunch launch, StderrPolicy stderr, StdinSource stdin) {
        List<String> args = launch.argv()
        launches << launch
        starts << args
        throw new IllegalStateException('start() not expected in a daemon-free unit spec: ' + args)
    }
}
