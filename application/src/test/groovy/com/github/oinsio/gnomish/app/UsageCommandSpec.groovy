package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.usage.json.UsageReportJsonMapper
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR14, NFR-C1 of add-git-workflow (task 5.6): {@code gnomish usage --dir <clone> <task>
 * [--json]} wires {@link UsageArgumentsParser}, {@link
 * com.github.oinsio.gnomish.adapter.git.UsageHistoryWalker}, {@link UsageTextRenderer}, and {@link
 * com.github.oinsio.gnomish.usage.json.UsageReportJsonMapper} together end to end against real
 * git fixtures (matching {@code StatusCommandSpec}'s adapter-layer convention). The seeded-clone
 * setup and round builder come from {@link SeededCloneFixture} (test-fixtures, shared with the
 * git adapter's own usage-walker specs).
 */
class UsageCommandSpec extends Specification implements SeededCloneFixture, StdoutCaptureFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupSeededClone()
    }

    private static UsageCommand newCommand() {
        new UsageCommand(TaskGitFixture.realClaimless(), liveConsole())
    }

    def "FR14: text render prints the stage/round table and a totals line"() {
        given:
        def passed = round(0, AttemptRecord.Result.PASSED, 1000, 100)
        persistRound('PROJ-1', TaskState.atStageStart('implement').recordUnburnedRound(passed), 'implement', 0)
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'PROJ-1')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('implement')
        output.contains('passed')
        output.contains('TOTAL')
    }

    def "FR14, NFR-C1: --json render prints the own version:1 mini-contract with full granularity"() {
        given:
        def passed = round(0, AttemptRecord.Result.PASSED, 1000, 100)
        persistRound('PROJ-2', TaskState.atStageStart('implement').recordUnburnedRound(passed), 'implement', 0)
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'PROJ-2', '--json')

        when:
        def output = captureStdout { newCommand().run(args) }

        then:
        output.contains('"version" : 1')
        output.contains('"taskId" : "PROJ-2"')
        output.contains('"claude-x"')
    }

    def "FR13, UX3: a task branch absent everywhere prints a plain not-found message and throws TaskNotFoundException, no stack trace"() {
        given:
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'NO-SUCH-TASK')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) {
            newCommand().run(args)
        }

        then:
        output.contains('task not found')
        output.contains('NO-SUCH-TASK')
    }

    def "FR13, UX3, D15: 'Deleted branch after merge' — a branch that existed and was deleted reports not-found the same as a never-existing task"() {
        given: 'a task branch created, its worktree removed, then the branch deleted — mirroring a merged-and-cleaned-up PR'
        persistRound('PROJ-3', TaskState.atStageStart('implement').recordUnburnedRound(round(0, AttemptRecord.Result.PASSED, 500, 10)), 'implement', 0)
        def worktree = worktreesRoot.resolve('clone').resolve('PROJ-3')
        runner.run(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        runner.run(cloneDir, 'branch', '-D', 'gnomish/PROJ-3')
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'PROJ-3')

        when:
        def output = captureStdoutExpectingThrow(TaskNotFoundException) {
            newCommand().run(args)
        }

        then:
        output.contains('task not found: PROJ-3')
    }

    def "UsageException: --dir is required"() {
        given:
        def args = new DefaultApplicationArguments('usage', 'PROJ-1')

        when:
        newCommand().run(args)

        then:
        thrown(UsageException)
    }

    def "FR14: UsageException: a task id is required"() {
        given:
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir)

        when:
        newCommand().run(args)

        then:
        thrown(UsageException)
    }

    // FR5, UX3 of harden-untrusted-text-sinks: the `--json` mini-contract is read by a parser, so
    // it goes out on the console owner's machine path, byte for byte. The path itself is what this
    // asserts — a human-path render of well-formed JSON still looks like JSON, and would corrupt
    // the document only for the characters that make the corruption worth catching.
    def "FR5, UX3: the --json mini-contract goes out on the machine path, byte for byte"() {
        given:
        def passed = round(0, AttemptRecord.Result.PASSED, 1000, 100)
        persistRound('MACHINE-1', TaskState.atStageStart('implement').recordUnburnedRound(passed), 'implement', 0)
        def found = TaskGitFixture.realClaimless().store().usageHistory(cloneDir, 'MACHINE-1') as UsageHistoryResult.Found
        def expected = new UsageReportJsonMapper().serialize('MACHINE-1', found.rows(), found.totals()) +
                ConsoleIO.LINE_END
        def console = new ScriptedConsoleIO()
        def args = new DefaultApplicationArguments('usage', '--dir=' + cloneDir, 'MACHINE-1', '--json')

        when:
        new UsageCommand(TaskGitFixture.realClaimless(), console).run(args)

        then: "exactly the mapper's own bytes, on the machine path"
        console.printedMachine == [expected]
    }
}
