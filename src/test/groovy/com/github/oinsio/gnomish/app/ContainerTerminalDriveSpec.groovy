package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.SandboxProperties
import com.github.oinsio.gnomish.adapter.environment.AdapterBinding
import com.github.oinsio.gnomish.adapter.environment.ScriptedSandboxDocker
import com.github.oinsio.gnomish.adapter.environment.Segment
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, D19 of add-sandbox-core: {@link ContainerTerminalDrive}'s aborted boundary, daemon-free —
 * a round whose persistence breaks the durability guarantee ends the run as Aborted, the outcome
 * is recorded on the last harvested tip and the exception rethrown, and keep semantics leave the
 * round box stopped (volume and network retained) rather than removed.
 */
class ContainerTerminalDriveSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    static final String KEY = 't-abort'

    @TempDir
    Path tempDir

    def gitRunner = new GitProcessRunner()
    def docker = new ScriptedSandboxDocker()
    def sandbox = new SandboxProperties('gnomish/img', null, null, null, [], [], false)
    Path cloneDir

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('instructions.md'), 'build it\n')
        gitRunner.run(cloneDir, 'add', 'instructions.md')
        gitRunner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
                'instructions.md', [],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    // D19: a broken round (the interactive round never opened an environment, so the sandboxed
    // persistence cannot durably commit it) aborts the run — the outcome commits on the branch,
    // the AbortedException escapes to the CLI boundary, and the box is kept stopped.
    def "a persistence failure records the aborted outcome, keeps the box stopped, and rethrows"() {
        given: 'a fresh container task whose round runs interactively (scripted console)'
        def definition = new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
        def segments = [
            new Segment(AdapterBinding.CONTAINER, [stage()])
        ]
        def environments = docker.environments(KEY, cloneDir, sandbox, tempDir.resolve('guard'))
        def support = new ContainerRunSupport(new GitProcessRunner(), cloneDir, 'T-ABORT', environments, segments)
        def context = new TaskContext('T-ABORT', 'title', 'body', List.<Decision> of())
        support.taskRepository().createTask(context, 'HEAD')
        def assembly = newAssembly()
        def originalErr = System.err
        System.err = new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8')

        when:
        ContainerTerminalDrive.run(
                assembly, support, definition, context, TaskState.atStageStart('build'),
                RunArguments.InteractiveMode.ALL, cloneDir, null)

        then: 'the durability break escapes as AbortedException, carrying the outcome'
        def e = thrown(AbortedException)
        e.outcome() != null

        and: 'the aborted outcome is durably recorded on the task branch tip'
        gitOutput(cloneDir, 'show', 'gnomish/T-ABORT:.gnomish-task/task.json').contains('"aborted"')

        and: 'keep semantics: the round box was stopped, never removed'
        docker.runs.contains(['stop', 'gnomish-box-' + KEY])
        !docker.runs.contains([
            'rm',
            '-f',
            'gnomish-box-' + KEY
        ])

        and: 'the runner-start orphan sweep ran (FR11): the factory-labelled container listing fired'
        docker.runs.any { it.first() == 'ps' && it.contains('label=com.github.oinsio.gnomish.factory') }

        cleanup:
        System.err = originalErr
    }
}
