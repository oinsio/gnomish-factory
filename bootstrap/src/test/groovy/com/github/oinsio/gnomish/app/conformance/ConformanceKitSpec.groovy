package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.RepositoryInit
import com.github.oinsio.gnomish.app.describe.ProgramChannel
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeEquipment
import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.executor.InProcessExecutorDouble
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12, FR23, FR24, M4 of define-executor-contract (design D18, D19): the conformance kit against
 * real candidate processes — the stand-in reference executor, the misbehaving fake told to break one
 * case, a candidate whose describe lists {@code verdict} alone. Each run is wrapped in the factory's
 * own {@code ProgramExecutor}, each case in a git repository of its own, every launch recorded. No
 * feature rests on a scheduling order: each start and poll is waited to its end before the next.
 */
class ConformanceKitSpec extends Specification {

    private static final Map<String, Object> DEFER = [defer: true]

    @TempDir
    Path tempDir

    private final List<List<String>> launched = []
    private final Set<Path> scratchAreas = [] as Set

    private KitReport verify(Path program, Map<String, Object> pendingSettings = [:]) {
        def counted = { ProcessBuilder builder, StdinSource stdin ->
            launched << List.copyOf(builder.command())
            String written = builder.environment()[ProgramChannel.RESULT_FILE_VARIABLE]
            if (written != null) {
                scratchAreas << Path.of(written).parent
            }
            ProcessLaunch.grouped().start(builder, stdin)
        } as ProcessLaunch
        def equipment = new KitEquipment(VirtualTimeEquipment.create(), TestChildEnvironment.hostAllowlist(), counted,
                new RepositoryInit(new GitProcessRunner()), Files.createTempDirectory(tempDir, 'stderr-'))
        def candidate = new ProgramCandidate([program.toString()], pendingSettings, Files.createTempDirectory(tempDir, 'kit-'), equipment)
        new ConformanceKit(pendingSettings, equipment.time().sleeper()).run(candidate)
    }

    private static List<KitCase> failed(KitReport report) {
        report.verdicts().findAll {
            it.value instanceof CaseVerdict.Failed
        }.keySet().toList()
    }

    // Scenario: executor/conformance-kit / Reference executor passes every case
    // Scenario: executor/wire-contract / Older executor survives a new optional field
    def "FR12, M4: the reference executor passes all twelve cases under its pending settings"() {
        when:
        def report = verify(StandIn.executor('reference'), DEFER)

        then:
        report.verdicts().keySet().toList() == KitCase.values().toList()
        report.verdicts().values().every { it instanceof CaseVerdict.Passed }
        report.failures() == 0
        report.lines() == KitCase.values().collect {
            "pass ${it.word()}".toString()
        }

        and: 'every case environment, the describe one included, was disposed with its scratch area'
        scratchAreas.size() == KitCase.values().size() - 1
        scratchAreas.every { !Files.exists(it) }

        and: 'the pending case started and polled one program instance, each operation its own process'
        launched*.last().count { it == 'poll' } == 1
    }

    // Scenario: executor/conformance-kit / Misbehaving fake fails each case it is told to break
    def "FR12, M4: the fake told to break #kase.word fails exactly that case, naming the violation"() {
        when:
        def report = verify(StandIn.executor("break-${kase.word()}"), DEFER)

        then:
        failed(report) == [kase]
        (report.verdicts()[kase] as CaseVerdict.Failed).detail().contains(detail)

        and: 'every other case passes, or — when the factory refused the describe answer — reaches no run'
        report.verdicts().findAll { it.key != kase }.every { key, verdict ->
            verdict instanceof CaseVerdict.Passed || (kase == KitCase.SETTINGS_SCHEMA && key != KitCase.DESCRIBE
            && verdict instanceof CaseVerdict.NotApplicable)
        }

        where:
        kase | detail
        KitCase.DESCRIBE | 'the describe document breaks its schema at enforcementLevel (enum)'
        KitCase.SETTINGS_SCHEMA | 'settings schema the factory cannot use'
        KitCase.TRANSFORM | 'wrote no result'
        KitCase.VERDICT | 'completed with no answer, although role verdict needs one'
        KitCase.DECISION | "chose 'not-an-option', which is none of the options"
        KitCase.UNKNOWN_ROLE | 'answered completed in role effect'
        KitCase.OVERSIZED_OUTPUT | 'the result document breaks its schema'
        KitCase.STDERR_NOISE | 'wrote no result'
        KitCase.NON_JSON_STDOUT | 'wrote no result'
        KitCase.PENDING_POLL | 'wrote no result'
        KitCase.UNKNOWN_REQUEST_FIELDS | 'answered failed/infrastructure'
        KitCase.MALFORMED_ANSWER | 'the result document breaks its schema at answer (oneOf)'
    }

    // Scenario: executor/conformance-kit / A built-in executor is driven through the same cases in process
    def "FR8, D19: the in-process double gets the program's verdict on every case off the wire, #preset"() {
        given:
        def inProcess = preset == 'reference' ? new InProcessExecutorDouble() : InProcessExecutorDouble.breaking(preset - 'break-')

        when:
        def onWire = verify(StandIn.executor(preset), DEFER)
        def offWire = new ConformanceKit([:], { d -> } as Sleeper)
        .run(new BuiltInCandidate(inProcess, Files.createTempDirectory(tempDir, 'built-in-')))

        then:
        KitCase.values().findAll { !it.wireOnly() }.every {
            onWire.verdicts()[it].getClass() == offWire.verdicts()[it].getClass()
        }
        KitCase.values().findAll {
            it.wireOnly()
        }.every {
            offWire.verdicts()[it] instanceof CaseVerdict.NotApplicable
        }

        where:
        preset << ['reference'] + InProcessExecutorDouble.BREAKABLE.collect {
            "break-${it}".toString()
        }
    }

    // Scenario: executor/conformance-kit / Undeclared role is refused by the candidate
    def "FR12: a start in a role the describe does not list passes when refused and fails when answered completed"() {
        when:
        def refused = verify(StandIn.executor('reference'))
        def answered = verify(StandIn.executor('break-unknown-role'))

        then:
        refused.verdicts()[KitCase.UNKNOWN_ROLE] instanceof CaseVerdict.Passed
        launched.count { it[-1] == 'start' } > 0
        answered.lines().contains('FAIL unknown-role: answered completed in role effect, which its describe does not list')
    }

    // Scenario: executor/conformance-kit / Role not declared is not exercised
    // Scenario: executor/conformance-kit / Pending case needs the poll capability
    def "FR12, FR23: a verdict-only candidate runs the verdict case, the other roles and the pending case are not applicable, and no poll is sent"() {
        when:
        def report = verify(StandIn.executor('verdict-only'))

        then:
        report.failures() == 0
        [
            KitCase.TRANSFORM,
            KitCase.DECISION,
            KitCase.MALFORMED_ANSWER,
            KitCase.PENDING_POLL
        ].every {
            report.verdicts()[it] instanceof CaseVerdict.NotApplicable
        }
        report.verdicts()[KitCase.VERDICT] instanceof CaseVerdict.Passed
        report.lines().contains('n/a  pending-poll: the describe declares no poll capability')
        report.lines().contains('n/a  transform: the describe lists no role transform')

        and: 'no poll reached the candidate'
        !launched.any { it.last() == 'poll' }
    }
}
