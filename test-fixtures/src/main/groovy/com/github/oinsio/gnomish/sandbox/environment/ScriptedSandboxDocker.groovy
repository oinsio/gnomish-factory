package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.DenialRestoration
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.file.Path
import java.time.Instant
import java.util.function.Supplier

/**
 * A scripted {@link RecordingDockerCli} whose answers make a full materialize +
 * self-check pass without a daemon (FR8, D5 of add-sandbox-core): fresh boxes
 * (container-state inspect fails), every create succeeds, the guard reports
 * running, the task network reports internal, the runtime reports {@code runc},
 * and the exec'd self-check curl probes behave (direct egress fails, the guard
 * denies with 403). Lets app-layer specs drive {@code ContainerRunSupport} and
 * its judge/round environments daemon-free, recording every docker argv for
 * assertions — the mutation-gate counterpart of the Docker-gated E2E layer.
 */
class ScriptedSandboxDocker extends RecordingDockerCli {

    /**
     * What {@code docker logs} of the guard container answers with — the guard's own stdout, one
     * {@code --timestamps} line per denial. Empty by default, so a scenario that is not about
     * denials sees a guard that blocked nothing; a scenario that is about them scripts the lines
     * whose parse it wants to assert on.
     */
    String guardLog = ''

    /**
     * What a {@code docker exec} of the agent CLI inside the box prints — the round's stream-json.
     * Defaults to the fake agent's {@code plain-round} scenario, so a container round driven by the
     * real CLI adapter completes the way it does on the host against the fake agent (FR6 of
     * remove-interactive-console); the fake binary itself cannot run inside a box no daemon backs.
     */
    String agentRound = FakeAgentScenario.stdout('plain-round')

    /**
     * What the image inspection answers with (its {@code .Config} JSON), or {@code null} — the
     * default — for an image declaring no volume and carrying this factory's contract label
     * ({@link RecordingDockerCli#answerDeclaredVolumes}). A scenario about the image-contract
     * refusal sets a {@code Config} without the label (design D22 of add-sandbox-base-image).
     */
    String imageConfig = null

    ScriptedSandboxDocker() {
        onRun = { List<String> args ->
            if (imageConfig != null && args == DockerCommands.inspectImageConfig(args.last())) {
                return DockerResult.of(0, imageConfig, '')
            }
            // Matched against the production command itself, never a copy of its format string:
            // the container-state inspect grows fields over time, and a stale copy here would
            // silently stop matching and turn the fresh-materialize path into the reattach path
            // (a green fixture answering the wrong question).
            if (args[0] == 'inspect' && args == DockerCommands.inspectContainerState(args.last())) {
                return DockerResult.of(1, '', 'No such object') // fresh materialize path
            }
            def declared = answerDeclaredVolumes(args)
            if (declared != null) {
                return declared
            }
            if (args[0] == 'inspect' && args.contains('{{.Id}}')) {
                return DockerResult.of(0, 'sha256:guard-container\n', '') // the guard's denial-source identity
            }
            if (args[0] == 'inspect' && args.contains('{{.State.Running}}')) {
                return DockerResult.of(0, 'true\n', '') // guard already running
            }
            if (args[0] == 'network' && args[1] == 'inspect') {
                return DockerResult.of(0, 'true\n', '') // task network is --internal
            }
            if (args[0] == 'inspect' && args.contains('{{.HostConfig.Runtime}}')) {
                return DockerResult.of(0, 'runc\n', '') // runtime matches the configured default
            }
            if (args[0] == 'logs') {
                return DockerResult.of(0, guardLog, '') // the guard's denial log, scripted per scenario
            }
            DockerResult.of(0, '', '')
        }
    }

    @Override
    Process start(DockerExecLaunch launch, StderrPolicy stderr, StdinSource stdin) {
        List<String> args = launch.argv()
        starts << args
        // The agent CLI's own exec: every round and vote asks for stream-json output.
        if (args.contains('stream-json')) {
            return new FakeExecProcess(0, agentRound)
        }
        // The two exec'd self-check probes: direct egress must fail; the proxied
        // probe of a denied host must observe the guard's 403.
        args.contains('--noproxy') ? new FakeExecProcess(1, '') : new FakeExecProcess(0, '403')
    }

    /**
     * Builds a per-task {@link ContainerEnvironments} over this scripted docker —
     * the daemon-free stand-in for {@code ContainerEnvironmentFactory.forTask}, exposed
     * here because the seam constructor and {@code DockerCli} are package-private.
     * {@code restoration} is what every round environment built here is offered (FR17 of
     * make-checkpoint-gate-durable); a spec whose flow reads the branch tip passes the
     * production supplier, the rest keep the default of nothing recorded.
     */
    ContainerEnvironments environments(
            String key,
            Path sourceClone,
            SandboxProperties sandbox,
            Path guardRoot,
            OwnershipMode mode = OwnershipMode.MANUAL,
            String projectId = 'test-project',
            Supplier<DenialRestoration> restoration = {
                -> DenialRestoration.none()
            }) {
        def timing = new BoxTiming(new TimeEquipment(
                        // A fixed instant git accepts as a commit date (the epoch is rejected), never the
                        // wall clock (FR21 of supervise-daemon-loops-and-embed-dashboard).
                        new VirtualClock(Instant.parse('2026-01-01T00:00:00Z')), { d -> } as Sleeper), DEFAULT_COMMAND_TIMEOUT)
        new ContainerEnvironments(
                new BoxEquipment(this, sandbox, timing, guardRoot, mode, ImageContractCheck.current()),
                key,
                // A scripted box changed nothing: its harvest lands the branch's own tip in the
                // task's quarantine, as a real harvest of an unchanged box does (design D18 of
                // tighten-spec-contract), so the admission finds the range already on the branch.
                new BoxGitLink(sourceClone, { String container, String branch ->
                    new GitProcessRunner().run(sourceClone, 'update-ref',
                    ContainerHarvestFetch.quarantine(branch).name(), 'refs/heads/' + branch)
                } as ContainerHarvest),
                ChildEnvAllowlist.none(),
                projectId,
                restoration)
    }

    /** Reads a fake-agent scenario's scripted stream from the {@code fake-agent} resources beside this fixture. */
    private static final class FakeAgentScenario {

        static String stdout(String scenario) {
            def resource = ScriptedSandboxDocker.getResource("/fake-agent/scenarios/${scenario}/stdout.jsonl")
            if (resource == null) {
                throw new IllegalStateException("no fake-agent scenario '${scenario}' on the test classpath")
            }
            resource.getText('UTF-8')
        }
    }

    /**
     * A finished child process with canned merged output — the exec seam's daemon-free stand-in.
     * The output is fed through the fake's own pipe ({@code writeStdout}, then the exit's release),
     * so it reads as a Linux pipe does (design D6 of make-pipe-reads-non-parking).
     */
    private static final class FakeExecProcess extends FakeProcess {

        FakeExecProcess(int exitCode, String output) {
            super(exitCode, output)
        }

        @Override
        OutputStream getOutputStream() {
            new ByteArrayOutputStream()
        }
    }
}
