package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.adapter.agent.fake.FakeAgentBinary
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Task 5.7 of add-base-ref-resolution: the end-to-end proof that {@link GitModeRunner}'s
 * {@code --base} wiring — {@link ManualRunLawBinding#of} and {@code GitFreshTaskSupport#createTask}
 * together — makes both the pipeline law and the task branch's pin come from the resolved base ref,
 * never from the clone's current checkout or its uncommitted edits, and that the un-based manual
 * run keeps binding the clone's working tree exactly as {@code ManualRunLawBindingSpec} already
 * proves at the unit level.
 *
 * <p>Sibling to {@link GitModeLawBindingSpec} rather than an extension of it: the release-branch
 * setup this file needs has nothing to do with that file's mid-run tamper scenario, and the two
 * together would exceed the project's file-size cap.
 *
 * <p>Implements FR11, FR8, M5 of add-base-ref-resolution.
 */
class BaseRefLawBindingSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    @TempDir
    Path tempDir

    private static final String MAIN_LAW = 'MAIN LAW: never seen by a based run.'
    private static final String RELEASE_LAW = 'RELEASE LAW: this is what a release/1.18 base must bind.'
    private static final String DIRTY_LAW = 'DIRTY UNCOMMITTED EDIT: never committed, never a base target.'

    Path cloneDir
    Path worktreesRoot
    String mainBranch

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'base-ref-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        commit(cloneDir, '.gnomish/instructions.md', MAIN_LAW + '\n')
        // The task names this branch "main", but the local git's init.defaultBranch config
        // decides the actual name (often "master" in CI/dev environments) — capture it rather
        // than hard-coding, so this spec proves the same guarantee under either default.
        mainBranch = gitOutput(cloneDir, 'symbolic-ref', '--short', 'HEAD')
        worktreesRoot = tempDir.resolve('worktrees-root')
    }

    private FactoryProperties fakeAgentProperties(String captureStdinPath) {
        def scriptPath = FakeAgentBinary.commandPrefix()[1]
        def wrapper = File.createTempFile('fake-agent-wrapper', '.sh')
        wrapper.text = """#!/bin/sh
export GNOMISH_FAKE_SCENARIO='plain-round'
export GNOMISH_FAKE_CAPTURE_STDIN='${captureStdinPath}'
exec sh '${scriptPath}' "\$@"
"""
        wrapper.setExecutable(true)
        wrapper.deleteOnExit()
        testProperties(agentCliBinary: wrapper.absolutePath, agentCliEnvPassthrough: [])
    }

    private static StageDefinition stage() {
        new StageDefinition(
                'build', 'purpose', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
                'instructions.md',
                [
                    new VerifyCheck.Builtin('files_exist', [files: ['output.txt']])
                ],
                new AutonomyLimits(3), AdvancementMode.AUTO)
    }

    private static PipelineDefinition pipeline() {
        new PipelineDefinition('1', new AutonomyLimits(3), [stage()])
    }

    private GitModeRunner runner(String captureStdinPath) {
        new GitModeRunner(
                newAssembly(new ByteArrayInputStream(new byte[0]), System.out, fakeAgentProperties(captureStdinPath)),
                TaskGitFixture.real(),
                worktreesRoot)
    }

    // FR11, FR8, M5 of add-base-ref-resolution: a --base override sends both the pipeline law and
    // the task branch's pin to that ref's own committed content — the clone's current checkout
    // (main) and its uncommitted working-tree edit play no part in either.
    def "a task based on release/1.18 binds law and pin from that branch, not from main or the clone's dirty edit"() {
        given: 'a release/1.18 branch with its own committed law, diverging from main'
        gitOutput(cloneDir, 'checkout', '-b', 'release/1.18')
        commit(cloneDir, '.gnomish/instructions.md', RELEASE_LAW + '\n')
        def releaseTip = gitOutput(cloneDir, 'rev-parse', 'HEAD')
        gitOutput(cloneDir, 'checkout', mainBranch)

        and: "the clone's working tree is dirtied with a third, never-committed law"
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), DIRTY_LAW + '\n')

        and: 'a captured-stdin file the fake writes its one prompt to'
        def captureFile = File.createTempFile('fake-agent-stdin', '.log')
        captureFile.deleteOnExit()
        def context = new TaskContext('BASE-1', 'title', 'body', List.<Decision> of())

        when: 'a fresh git-mode run based on release/1.18'
        runner(captureFile.absolutePath).run(cloneDir, 'release/1.18', pipeline(), context,
                TaskState.atStageStart('build'), RunArguments.InteractiveMode.NONE)

        then: 'the run reached Completed and left the delivered branch behind'
        gitExitCode(cloneDir, 'rev-parse', '--verify', 'gnomish/BASE-1') == 0

        and: 'the pin came from release/1.18\'s own tip, not from main'
        gitExitCode(cloneDir, 'merge-base', '--is-ancestor', releaseTip, 'gnomish/BASE-1') == 0

        and: "the one round's prompt carried release/1.18's law — never main's, never the dirty edit"
        def prompt = captureFile.text
        prompt.contains(RELEASE_LAW)
        !prompt.contains(MAIN_LAW)
        !prompt.contains(DIRTY_LAW)

        and: "the clone's own working tree was never touched — it still holds the dirty, uncommitted edit"
        Files.readString(cloneDir.resolve('.gnomish/instructions.md')).contains(DIRTY_LAW)
    }

    // FR8, M5 of add-base-ref-resolution: the contrast case — with no --base at all, nothing was
    // resolved, so the clone's working tree stays the law source and an uncommitted edit is what
    // the run actually binds, exactly as ManualRunLawBindingSpec proves at the unit level.
    def "a manual run with no --base still binds the clone's uncommitted working-tree edit"() {
        given: 'the clone on main, dirtied with an uncommitted law edit'
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), DIRTY_LAW + '\n')

        and: 'a captured-stdin file the fake writes its one prompt to'
        def captureFile = File.createTempFile('fake-agent-stdin', '.log')
        captureFile.deleteOnExit()
        def context = new TaskContext('BASE-2', 'title', 'body', List.<Decision> of())

        when: 'a fresh git-mode run with no --base'
        runner(captureFile.absolutePath).run(cloneDir, null, pipeline(), context,
                TaskState.atStageStart('build'), RunArguments.InteractiveMode.NONE)

        then: 'the run reached Completed'
        gitExitCode(cloneDir, 'rev-parse', '--verify', 'gnomish/BASE-2') == 0

        and: "the round's prompt carried the clone's uncommitted dirty law, not the committed main law"
        def prompt = captureFile.text
        prompt.contains(DIRTY_LAW)
        !prompt.contains(MAIN_LAW)
    }
}
