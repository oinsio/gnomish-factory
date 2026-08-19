package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.domain.engine.port.Clock
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.SandboxProperties
import java.nio.file.Path
import java.time.Instant

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

    ScriptedSandboxDocker() {
        onRun = { List<String> args ->
            if (args[0] == 'inspect' && args.contains('{{.State.Running}} {{.State.FinishedAt}}')) {
                return new DockerResult(1, '', 'No such object') // fresh materialize path
            }
            if (args[0] == 'inspect' && args.contains('{{.Id}}')) {
                return new DockerResult(0, 'sha256:guard-container\n', '') // the guard's denial-source identity
            }
            if (args[0] == 'inspect' && args.contains('{{.State.Running}}')) {
                return new DockerResult(0, 'true\n', '') // guard already running
            }
            if (args[0] == 'network' && args[1] == 'inspect') {
                return new DockerResult(0, 'true\n', '') // task network is --internal
            }
            if (args[0] == 'inspect' && args.contains('{{.HostConfig.Runtime}}')) {
                return new DockerResult(0, 'runc\n', '') // runtime matches the configured default
            }
            new DockerResult(0, '', '')
        }
    }

    @Override
    Process start(List<String> args, boolean mergeStderr) {
        starts << args
        // The two exec'd self-check probes: direct egress must fail; the proxied
        // probe of a denied host must observe the guard's 403.
        args.contains('--noproxy') ? new FakeExecProcess(1, '') : new FakeExecProcess(0, '403')
    }

    /**
     * Builds a per-task {@link ContainerEnvironments} over this scripted docker —
     * the daemon-free stand-in for {@code ContainerEnvironments.forTask}, exposed
     * here because the seam constructor and {@code DockerCli} are package-private.
     */
    ContainerEnvironments environments(String key, Path sourceClone, SandboxProperties sandbox, Path guardRoot) {
        new ContainerEnvironments(
                this, key, sourceClone,
                { String container, String branch -> } as ContainerHarvest,
                sandbox,
                { -> Instant.now() } as Clock,
                ChildEnvAllowlist.none(),
                { d -> } as Sleeper,
                guardRoot)
    }

    /** A finished child process with canned merged output — the exec seam's daemon-free stand-in. */
    private static final class FakeExecProcess extends Process {

        private final int exitCode
        private final byte[] output

        FakeExecProcess(int exitCode, String output) {
            this.exitCode = exitCode
            this.output = output.getBytes('UTF-8')
        }

        @Override
        OutputStream getOutputStream() {
            new ByteArrayOutputStream()
        }

        @Override
        InputStream getInputStream() {
            new ByteArrayInputStream(output)
        }

        @Override
        InputStream getErrorStream() {
            new ByteArrayInputStream(new byte[0])
        }

        @Override
        int waitFor() {
            exitCode
        }

        @Override
        int exitValue() {
            exitCode
        }

        @Override
        void destroy() {
        }
    }
}
