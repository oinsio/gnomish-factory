package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.check.PinCheck
import com.github.oinsio.gnomish.adapter.scm.actions.GithubActionsExecutor
import com.github.oinsio.gnomish.app.workspace.fake.AttemptCommitWorkspaces
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.BuiltInExecutors
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.gitobjects.CommitRequest
import com.github.oinsio.gnomish.gitobjects.GitObjectsFixture
import com.github.oinsio.gnomish.gitobjects.TreeEdit
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, FR16 of migrate-verify-to-executor-contract (design D17, D18): the check dispatch resolves the
 * {@code github-actions} built-in the operator connected by configuration alone, and binds it behind
 * the pin decorator its describe's {@code checkIdIsPinnedPath} selects — so a workflow file the gnome
 * rewrote fails the check before the executor's first {@code start}, with no pin path in the manifest
 * and no platform contact (the connection points at a loopback port nothing listens on).
 */
class CheckDispatchPinSpec extends Specification implements GitObjectsFixture {

    static final String WORKFLOW = '.github/workflows/ci.yml'

    @TempDir
    Path tempDir

    static VerifyCheck.Executor actions() {
        new VerifyCheck.Executor(VerifyCheck.Token.EXTERNAL, BuiltInExecutors.GITHUB_ACTIONS, WORKFLOW, [:],
        Duration.ofSeconds(1), Duration.ofMinutes(1), VerifyCheck.TimeoutClass.QUALITY, [],
        VerifyCheck.VerifyIn.FRESH_BOX, Threshold.ERROR, [])
    }

    // Scenario: verification/github-external-check / Operator enables the adapter with config alone
    // Scenario: verification/verdict-executors / Pin contribution survives
    def "FR16: a github-actions check whose workflow the gnome rewrote fails the pin before the first start"() {
        given: 'the law commit with the workflow, and an attempt that rewrote it'
        def bare = seedBareRepo(tempDir, [(WORKFLOW): "name: ci\n"])
        def objects = openGitObjects(bare, tempDir)
        def law = objects.resolveRef('refs/heads/base').orElseThrow()
        def attempt = objects.commit(new CommitRequest('refs/heads/gnomish/task-1', Optional.empty(), law,
                [
                    new TreeEdit.PutFile(WORKFLOW, 'name: weakened'.getBytes(StandardCharsets.UTF_8))
                ], metadata()))

        and: 'the run registry over the operator\'s configured connection, and the run\'s pin-check'
        def dispatch = new CheckDispatch(ScmBuiltInRegistry.assemble(), new PinCheck(objects, law), VerdictInput.IDENTIFIER_ONLY)
        def workspace = AttemptCommitWorkspaces.at(attempt.hex())

        when:
        def result = dispatch.bind(actions(), CheckRef.of(0, actions()), workspace).execute(request(workspace))

        then: 'the built-in declares the flag, and the manifest named no pin path'
        GithubActionsExecutor.describeAnswer().checkIdIsPinnedPath()
        actions().pinPaths().isEmpty()

        and: 'the round completed with one error result on the workflow, the executor never reached'
        result.status() instanceof ExecutionResult.Completed
        def answer = (result.answer() as ExecutionResult.VerdictReply).answer() as VerdictAnswer.Sarif
        answer.results()*.level() == [ResultLevel.ERROR]
        answer.results()[0].finding().location() == WORKFLOW
        answer.results()[0].finding().message().contains('differs from the base branch')
    }

    private static StageExecutor.Request request(def workspace) {
        new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'), UntrustedText.tracker('b'), []),
        new StageDefinition('build', 'p', [], [], new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]),
        'i.md', [], new AutonomyLimits(3), AdvancementMode.AUTO), workspace, 0, [], actions(), CheckRef.of(0, actions()))
    }
}
