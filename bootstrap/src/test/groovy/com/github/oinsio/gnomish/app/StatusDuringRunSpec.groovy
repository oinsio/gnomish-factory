package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout
import spock.util.concurrent.PollingConditions

/**
 * {@code gnomish status} beside a running {@code gnomish run} (FR6 of make-run-headless): the
 * report comes from the task branch, and the run — which has no prompt and reads no input — goes on
 * to its end unaffected. The run's round is the fake agent's slow one, so the status request lands
 * while the round is in flight.
 *
 * <p>Implements FR6 of make-run-headless.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class StatusDuringRunSpec extends Specification implements BareGitRepoFixture, AppAssemblyFixture {

    private static final String TASK = 'MID-1'

    @TempDir
    Path tempDir

    /** The last status report printed, and whether the run had ended when that invocation began. */
    String lastReport
    boolean runDoneAtLastStatus

    // Scenario: cli/manual-run / Status mid-prompt
    def "FR6: status during a run prints the branch's report, and the run reads no input and finishes"() {
        given: 'a registered clone and a run whose stdin fails the spec if it is ever read'
        def cloneDir = initWorkingRepo(tempDir, 'mid-run-project')
        Files.createDirectories(cloneDir.resolve('.gnomish'))
        Files.writeString(cloneDir.resolve('.gnomish/instructions.md'), 'build it\n')
        commitAll(cloneDir)
        RegisteredClone clone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
        def reads = []
        def stdin = new InputStream() {
                    @Override
                    int read() {
                        reads << 'read'
                        -1
                    }
                }
        def runner = new GitModeRunner(newAssembly(stdin, new PrintStream(new ByteArrayOutputStream(), true, 'UTF-8'),
                FakeAgentSupport.propertiesFor('plain-round-slow')), TaskGitFixture.real(), clone, LiveConsoleIO.onStdout())

        when: 'the run starts, and while its slow round is in flight the operator asks for the status'
        def running = CompletableFuture.runAsync {
            runner.run(new RunOrder(cloneDir, null, pipeline(), false),
            new TaskContext(TASK, UntrustedText.tracker('mid-run title'), UntrustedText.tracker('body'), List.<Decision> of()),
            TaskState.atStageStart('build'))
        }
        new PollingConditions(timeout: 30).eventually {
            assert statusWhile(running, clone, cloneDir).contains('mid-run title')
        }

        then: 'the report was read from the branch while the run was still going'
        lastReport.contains("Task: ${TASK}")
        !runDoneAtLastStatus

        when: 'the run is left to finish'
        running.get(60, TimeUnit.SECONDS)

        then: 'it reached its end untouched, and nothing ever read its input'
        noExceptionThrown()
        reads.isEmpty()
    }

    /**
     * One {@code gnomish status <task>} invocation over the clone, its printed report joined; records
     * whether the run had already ended when the invocation began.
     */
    private String statusWhile(CompletableFuture<?> running, RegisteredClone clone, Path cloneDir) {
        runDoneAtLastStatus = running.isDone()
        def console = new ScriptedConsoleIO()
        new StatusCommand(TaskGitFixture.realClaimless(), RegisteredCloneFixture.scope(clone), console)
                .run(new DefaultApplicationArguments('status', "--dir=${cloneDir}".toString(), TASK))
        lastReport = console.printed.join('\n')
    }

    private static PipelineDefinition pipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'claude-fake-main-1', [:]),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }
}
