package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageFold
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR18 of define-executor-contract (design D15): {@code gnomish usage} re-folds the rounds it walked
 * and compares the result with the {@code totals} the tip records; a difference is an error naming
 * both figures, and no report is rendered. The tip is tampered with the way a writer bug would do
 * it: the real round persistence writes a state whose {@code totals} no fold of its rounds produced.
 */
class UsageTotalsMismatchSpec extends Specification implements SeededCloneFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupSeededClone()
    }

    private static final UsageSnapshot TAMPERED = new UsageSnapshot(
    [(ExecutorRole.TRANSFORM): ['claude-x': new TokenUsage(999, 10, 0, 0)]],
    new UsageCost(BigDecimal.ZERO, [(ExecutorRole.TRANSFORM): ['claude-x': new TokenUsage(999, 10, 0, 0)]], false),
    1, null)

    /** One passing round persisted under {@code totals} written by hand; returns the honest fold. */
    private UsageSnapshot persistTampered(String taskId) {
        def passed = round(taskId, 'implement', 0, AttemptRecord.Result.PASSED, 1000, 100)
        def honest = TaskState.atStageStart('implement').recordUnburnedRound(passed, ExecutorVersions.none())
        persistRound(taskId, new TaskState(honest.position(), honest.attemptsUsed(), honest.attempts(), TAMPERED,
                honest.executorVersions()), 'implement', 0)
        UsageFold.foldAll(honest.attempts())
    }

    private UsageCommand command(ScriptedConsoleIO console) {
        new UsageCommand(TaskGitFixture.realClaimless(), RegisteredCloneFixture.scope(registeredClone), console, new VirtualClock())
    }

    // Scenario: cli/task-inspection / A mismatch between the fold and the tip is an error
    def "FR18: a tip whose totals differ from the fold of the walked rows ends usage with both figures and no report"() {
        given:
        def folded = persistTampered('PROJ-1')
        def console = new ScriptedConsoleIO()
        def args = new DefaultApplicationArguments([
            'usage',
            '--dir=' + cloneDir,
            'PROJ-1'
        ] + flags as String[])

        when:
        command(console).run(args)

        then: 'the error names the fold and the recorded figure'
        def mismatch = thrown(UsageTotalsMismatchException)
        mismatch.message.contains('PROJ-1')
        mismatch.message.contains('folded:   ' + UsageTotalsMismatchException.describe(folded))
        mismatch.message.contains('recorded: ' + UsageTotalsMismatchException.describe(TAMPERED))
        folded != TAMPERED

        and: 'nothing was rendered on either path'
        console.printed.isEmpty()
        console.printedMachine.isEmpty()

        where:
        flags << [[], ['--json']]
    }

    def "FR18: one figure is spelled in full — position, tokens per role and model in key order, cost"() {
        given:
        def tokens = { long n -> new TokenUsage(n, n + 1, n + 2, n + 3) }
        def snapshot = new UsageSnapshot(
                [(ExecutorRole.VERDICT): ['b': tokens(5)], (ExecutorRole.TRANSFORM): ['z': tokens(9), 'a': tokens(1)]],
                new UsageCost(new BigDecimal('0.50'), [(ExecutorRole.VERDICT): ['b': tokens(5)]], false),
                3, RoundToken.of('d' * 40))

        expect:
        UsageTotalsMismatchException.describe(snapshot) == 'rounds=3 through=' + 'd' * 40 +
                ' tokens=[TRANSFORM/a in=1 out=2 cacheCreation=3 cacheRead=4;' +
                ' TRANSFORM/z in=9 out=10 cacheCreation=11 cacheRead=12; VERDICT/b in=5 out=6 cacheCreation=7 cacheRead=8]' +
                ' priced=0.50 unpriced=[VERDICT/b in=5 out=6 cacheCreation=7 cacheRead=8] complete=false'
        UsageTotalsMismatchException.describe(UsageSnapshot.EMPTY) ==
                'rounds=0 through=none tokens=[] priced=0 unpriced=[] complete=true'
    }
}
