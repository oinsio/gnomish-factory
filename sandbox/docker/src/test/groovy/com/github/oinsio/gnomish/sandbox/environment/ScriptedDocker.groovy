package com.github.oinsio.gnomish.sandbox.environment

import com.github.oinsio.gnomish.sandbox.StderrPolicy
import com.github.oinsio.gnomish.subprocess.StdinSource
import java.nio.charset.StandardCharsets

/**
 * Shared scripted test doubles for {@link ContainerTaskExecutionEnvironment} exec
 * specs: a {@link DockerCli} whose every management call (inspect/create/...)
 * succeeds and whose every {@code start} returns one scripted {@link Process},
 * recording the argv and the {@link StdinSource} it was handed.
 * Extracted from {@code ContainerTaskExecutionEnvironmentExecSpec} so
 * {@code ContainerExecHandleSpec} shares the same fixture rather than
 * duplicating it.
 */
final class ScriptedDockerCli extends DockerCli {
    final List<List<String>> starts = []
    final List<StdinSource> sources = []
    final List<StderrPolicy> stderrs = []
    private final Process process

    ScriptedDockerCli(Process process) {
        super('docker')
        this.process = process
    }

    @Override
    DockerResult run(List<String> args) {
        def declared = RecordingDockerCli.answerDeclaredVolumes(args)
        if (declared != null) {
            return declared
        }
        def probe = RecordingDockerCli.answerBoxGitVersion(args)
        if (probe != null) {
            return probe
        }
        args[0] == 'inspect' ? DockerResult.of(1, '', 'No such object') : DockerResult.of(0, '', '')
    }

    @Override
    Process start(DockerExecLaunch launch, StderrPolicy stderr, StdinSource stdin) {
        List<String> args = launch.argv()
        starts << args
        sources << stdin
        stderrs << stderr
        process
    }
}

/**
 * A {@link Process} double that has exited with 0 and canned stdout, read through {@link FakeProcess}'s
 * own pipe (design D6 of make-pipe-reads-non-parking).
 */
final class ScriptedProcess extends FakeProcess {

    ScriptedProcess() {
        this(new byte[0])
    }

    ScriptedProcess(byte[] stdout) {
        super(0, new String(stdout, StandardCharsets.UTF_8))
    }
}
