package com.github.oinsio.gnomish.app.conformance

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Executor
import com.github.oinsio.gnomish.executorcontract.ExecutorRequest
import com.github.oinsio.gnomish.executorcontract.PendingKey
import com.github.oinsio.gnomish.testfixtures.executor.InProcessExecutorDouble
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8, FR24 of define-executor-contract (design D2, D18, D19): a built-in driven through the kit's
 * core in process — the in-process double of {@code :test-fixtures}, well behaved and told to break one
 * case. No process is spawned: {@link BuiltInCandidate} holds no launch, and each case leaves only its
 * empty working copy behind.
 */
class BuiltInCandidateSpec extends Specification {

    private static final List<KitCase> WIRE_ONLY = KitCase.values().findAll {
        it.wireOnly()
    }

    @TempDir
    Path scratch

    private final List<Duration> waits = []

    private KitReport run(Executor executor) {
        new ConformanceKit([:], { Duration d ->
            waits << d
        } as Sleeper).run(new BuiltInCandidate(executor, scratch))
    }

    private static List<KitCase> failed(KitReport report) {
        report.verdicts().findAll {
            it.value instanceof CaseVerdict.Failed
        }.keySet().toList()
    }

    // Scenario: executor/conformance-kit / A built-in executor is driven through the same cases in process
    def "FR8, D19: the well-behaved double passes every case off the wire, the four wire-only cases are not applicable, and nothing is spawned"() {
        given:
        def builtIn = new InProcessExecutorDouble()

        when:
        def report = run(builtIn)

        then:
        report.failures() == 0
        report.verdicts().findAll {
            it.value instanceof CaseVerdict.NotApplicable
        }.keySet().toList() == WIRE_ONLY
        WIRE_ONLY*.word() == [
            'oversized-output',
            'stderr-noise',
            'non-json-stdout',
            'unknown-request-fields'
        ]
        report.lines().contains('n/a  non-json-stdout: the case exists only on the wire, and this executor runs in process')

        and: 'the pending case went pending by construction, under empty settings, and its one poll ended it'
        builtIn.asked.count { it.op == 'poll' } == 1
        builtIn.asked.find {
            it.op == 'poll'
        }.key == new PendingKey(InProcessExecutorDouble.PENDING_KEY)
        builtIn.asked.every {
            (it.request as ExecutorRequest).settings().size() == 0
        }
        waits == []

        and: 'each case that ran left its own empty working copy and nothing else'
        Files.list(scratch).withCloseable {
            it.map { p ->
                p.fileName.toString()
            }.sorted().toList()
        } ==
        [
            'decision',
            'malformed-answer',
            'pending-poll',
            'transform',
            'unknown-role',
            'verdict'
        ]
        Files.list(scratch).withCloseable {
            it.allMatch { p ->
                Files.list(p).withCloseable { c ->
                    c.count()
                } == 0
            }
        }
    }

    def "FR8, FR24: the double told to break #kase.word fails exactly that case: #detail"() {
        when:
        def report = run(InProcessExecutorDouble.breaking(kase.word()))

        then:
        failed(report) == [kase]
        (report.verdicts()[kase] as CaseVerdict.Failed).detail().contains(detail)
        report.verdicts().findAll { it.key != kase }.every { key, verdict ->
            verdict instanceof CaseVerdict.Passed || (verdict instanceof CaseVerdict.NotApplicable
            && (key.wireOnly() || (kase == KitCase.SETTINGS_SCHEMA && key != KitCase.DESCRIBE)))
        }

        where:
        kase | detail
        KitCase.DESCRIBE | 'the describe document breaks its schema at roles (uniqueItems)'
        KitCase.SETTINGS_SCHEMA | 'the factory cannot use the settings schema'
        KitCase.TRANSFORM | 'answered failed/infrastructure: the change was not made'
        KitCase.VERDICT | 'completed with no answer, although role verdict needs one'
        KitCase.DECISION | "chose 'not-an-option', which is none of the options"
        KitCase.UNKNOWN_ROLE | 'answered completed in role effect'
        KitCase.PENDING_POLL | "answered failed/infrastructure: no pending run has the key 'in-process-deferred'"
        KitCase.MALFORMED_ANSWER | 'answered a malformed answer'
    }

    def "FR8, D19: a wire-only case named to the double has nothing in process to break: #kase.word stays not applicable"() {
        when:
        def report = run(InProcessExecutorDouble.breaking(kase.word()))

        then:
        report.failures() == 0
        report.verdicts()[kase] instanceof CaseVerdict.NotApplicable

        where:
        kase << WIRE_ONLY
    }

    def "FR8: the double's describe, written back through the contract's writer, is the document the settings-schema case reads"() {
        when:
        def described = new BuiltInCandidate(new InProcessExecutorDouble(), scratch).describe()

        then:
        described.answer() == new InProcessExecutorDouble().describe()
        described.document().path('settingsSchema').toString() == '{"type":"object"}'
        described.document().path('roles')*.asText() == [
            'transform',
            'verdict',
            'decision'
        ]
        described.violations() == []
        described.refusals() == []
    }

    def "FR8: an executor that throws from #operation fails #kase.word naming the exception's class, not its message"() {
        given:
        def conforming = new InProcessExecutorDouble()
        def boom = { Object[] args ->
            throw new IllegalStateException('secret detail')
        }
        Map behaviour = [describe: {
                conforming.describe()
            }, start: { r ->
                conforming.start(r)
            }, poll: { r, k ->
                conforming.poll(r, k)
            }]
        behaviour[operation] = boom

        when:
        def report = run(behaviour as Executor)
        def verdict = report.verdicts()[kase]

        then:
        verdict instanceof CaseVerdict.Failed
        verdict.detail() == "the executor threw java.lang.IllegalStateException from Executor.${operation} where the contract asks for a result".toString()
        !report.lines().any { it.contains('secret detail') }

        where:
        operation | kase
        'describe' | KitCase.DESCRIBE
        'start' | KitCase.TRANSFORM
        'poll' | KitCase.PENDING_POLL
    }
}
