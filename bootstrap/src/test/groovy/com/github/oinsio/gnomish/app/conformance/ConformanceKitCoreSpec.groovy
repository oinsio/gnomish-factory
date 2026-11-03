package com.github.oinsio.gnomish.app.conformance

import static com.github.oinsio.gnomish.app.conformance.KitSubjectDouble.*

import com.github.oinsio.gnomish.domain.engine.port.Sleeper
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.PendingKey
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.SettingsSchema
import com.github.oinsio.gnomish.executorcontract.Status
import java.time.Duration
import spock.lang.Specification

/**
 * FR8, FR12, FR24 of define-executor-contract (design D2, D19): the kit's core against the
 * in-process {@link KitSubjectDouble} — which cases apply, what each request asks, and how the
 * describe and settings-schema cases read what describing the candidate yielded.
 */
class ConformanceKitCoreSpec extends Specification {

    private final Sleeper sleeper = { Duration d -> } as Sleeper

    private KitReport run(KitSubjectDouble subject, Map<String, Object> pending = [:]) {
        new ConformanceKit(pending, sleeper).run(subject)
    }

    private static String asked(KitSubjectDouble subject) {
        subject.asked.collect {
            "${it.kase.word()}:${it.op}:${it.request.role().wire()}"
        }.join(' ')
    }

    def "FR12: a conforming subject passes every case; each case asks once, in its own role, workspace and settings"() {
        given:
        def subject = new KitSubjectDouble(answer: { r, k ->
            k == null && r.settings().path('defer').asBoolean() ? result(Status.PENDING, null, 'k1') : conforming(r)
        })

        when:
        def report = run(subject, [defer: true])

        then:
        report.failures() == 0 && report.verdicts().values().every {
            it instanceof CaseVerdict.Passed
        }
        asked(subject) == 'transform:start:transform verdict:start:verdict decision:start:decision unknown-role:start:effect ' +
                'oversized-output:start:transform stderr-noise:start:transform non-json-stdout:start:transform ' +
                'pending-poll:start:transform pending-poll:poll:transform unknown-request-fields:start:transform ' +
                'malformed-answer:start:decision'
        subject.asked.every {
            it.request.workspace().path() == "/w/${it.kase.word()}".toString()
        }
        subject.asked.collect {
            it.request.settings().toString()
        }.toSet() == ['{}', '{"defer":true}'] as Set
        subject.asked.findAll {
            it.request.settings().size() > 0
        }*.kase.unique() == [KitCase.PENDING_POLL]
        subject.asked.find { it.op == 'poll' }.key == new PendingKey('k1')
        subject.asked.find {
            it.kase == KitCase.DECISION
        }.request.roleInputs().options() == CaseJudgement.OPTIONS
    }

    def "FR8, D19: off the wire exactly the four wire-only cases are not applicable"() {
        when:
        def report = run(new KitSubjectDouble(wire: false))

        then:
        report.verdicts().findAll {
            it.value instanceof CaseVerdict.NotApplicable
        }.keySet() == KitCase.values().findAll {
            it.wireOnly()
        } as Set
        report.lines().contains('n/a  stderr-noise: the case exists only on the wire, and this executor runs in process')
        KitCase.values().findAll {
            it.wireOnly()
        }*.word() == [
            'oversized-output',
            'stderr-noise',
            'non-json-stdout',
            'unknown-request-fields'
        ]
        KitCase.values().findAll {
            it.role() != null
        }*.word() == [
            'transform',
            'verdict',
            'decision',
            'malformed-answer'
        ]
    }

    def "FR12: with roles #roles the undeclared-role case reports '#line'"() {
        given:
        def subject = new KitSubjectDouble(described: new KitSubject.Described(describeOf(roles, []), null, [], []),
        answer: { r, k -> completed ? result(Status.COMPLETED) : failed(null) })

        expect:
        run(subject).verdicts()[KitCase.UNKNOWN_ROLE].line(KitCase.UNKNOWN_ROLE) == line

        where:
        roles | completed || line
        LIVE + [Role.EFFECT] | true || 'n/a  unknown-role: the describe lists every role'
        [
            Role.EFFECT,
            Role.VERDICT,
            Role.DECISION
        ] | true || 'FAIL unknown-role: answered completed in role transform, which its describe does not list'
        LIVE | false || 'pass unknown-role'
    }

    def "FR12, FR23: a describe without a live role, or without poll, leaves its cases not applicable"() {
        when:
        def none = run(new KitSubjectDouble(described: new KitSubject.Described(describeOf([Role.EFFECT]), null, [], [])))
        def noPoll = run(new KitSubjectDouble(described: new KitSubject.Described(describeOf(LIVE, []), null, [], [])))

        then:
        none.lines().contains('n/a  oversized-output: the describe lists no role transform, verdict or decision')
        none.lines().contains('n/a  malformed-answer: the describe lists no role decision')
        none.lines().contains('n/a  pending-poll: the describe lists no role transform, verdict or decision')
        noPoll.lines().contains('n/a  pending-poll: the describe declares no poll capability')
    }

    def "FR12, FR24: a refused describe answer reaches no run; describe says '#describeLine', settings-schema '#settingsLine'"() {
        given:
        def subject = new KitSubjectDouble(described: new KitSubject.Described(null, document == null ? null : tree(document), breaches, refusals))

        when:
        def report = run(subject)

        then:
        report.verdicts()[KitCase.DESCRIBE].line(KitCase.DESCRIBE) == describeLine
        report.verdicts()[KitCase.SETTINGS_SCHEMA].line(KitCase.SETTINGS_SCHEMA) == settingsLine
        report.lines().contains('n/a  transform: the factory refused the describe answer, so no run reaches the candidate')
        subject.asked == []

        where:
        document | breaches | refusals || describeLine | settingsLine
        null | [] | [] || 'FAIL describe: the factory obtained no describe answer' | NO_DOCUMENT
        '[1]' | [breach()] | ['refused'] || 'FAIL describe: ' + breach().detail() | NO_DOCUMENT
        '{"settingsSchema":{}}' | [] | ['protocol 2'] || 'FAIL describe: protocol 2' | 'pass settings-schema'
        REMOTE | [] | ['cannot use it'] || 'pass describe' | 'FAIL settings-schema: cannot use it'
        REMOTE | [] | [] || 'pass describe' | 'FAIL settings-schema: the factory cannot use the settings schema: ' + unusable()
        REQUIRED | [] | [] || 'pass describe' | "FAIL settings-schema: the settings schema rejects the settings the kit sends (rule 'required'): " + rejected()
    }

    def "FR8, FR12: an accepted answer whose settings schema refuses the kit's settings reaches no run, as the preflight's refusal would"() {
        given:
        def subject = new KitSubjectDouble(described: new KitSubject.Described(describeOf(), tree(REQUIRED), [], []))

        when:
        def report = run(subject)

        then:
        report.verdicts()[KitCase.SETTINGS_SCHEMA] instanceof CaseVerdict.Failed
        report.verdicts()[KitCase.DESCRIBE] instanceof CaseVerdict.Passed
        report.lines().contains('n/a  transform: the settings schema refuses the settings the kit sends, so no run reaches the candidate')
        report.verdicts().findAll {
            !(it.key in [
                KitCase.DESCRIBE,
                KitCase.SETTINGS_SCHEMA
            ])
        }.values().every {
            it instanceof CaseVerdict.NotApplicable
        }
        subject.asked == []
    }

    def "FR24: a describe breach fails the describe case although the factory accepted the answer"() {
        when:
        def report = run(new KitSubjectDouble(described: new KitSubject.Described(describeOf(), tree('{"settingsSchema":{}}'), [breach()], [])))

        then:
        report.verdicts()[KitCase.DESCRIBE] == new CaseVerdict.Failed(breach().detail())
        report.verdicts()[KitCase.TRANSFORM] instanceof CaseVerdict.Passed
    }

    private static final String NO_DOCUMENT = 'n/a  settings-schema: the candidate wrote no describe document to read a settings schema from'
    private static final String REMOTE = '{"settingsSchema":{"$ref":"https://x.example/s.json"}}'
    private static final String REQUIRED = '{"settingsSchema":{"required":["n"]}}'

    private static String unusable() {
        SettingsSchema.judge(tree(REMOTE).path('settingsSchema'), [:]).reason().excerpt(300)
    }

    private static String rejected() {
        SettingsSchema.judge(tree(REQUIRED).path('settingsSchema'), [:]).violations().first().explanation().excerpt(300)
    }
}
