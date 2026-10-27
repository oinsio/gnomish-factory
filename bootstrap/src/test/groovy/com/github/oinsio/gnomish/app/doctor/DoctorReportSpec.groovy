package com.github.oinsio.gnomish.app.doctor

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.app.port.console.ConsoleIO
import spock.lang.Specification

/**
 * FR5, FR6, UX1, UX2 of add-doctor-command (design D5): the text rendering is one line per result
 * — status, name, detail and the fix on the same line — then a one-line summary; the JSON rendering
 * is the same results with their stable ids; the failure count decides the exit code.
 */
class DoctorReportSpec extends Specification {

    private static final String END = ConsoleIO.LINE_END

    def "FR5: each result is one line, the fix on that line, then the summary"() {
        given:
        def report = new DoctorReport([
            new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.OK, 'git 2.56.0 meets the floor', null),
            new DoctorVerdict(DoctorCheckId.GUARD_IMAGE, DoctorStatus.WARN, 'absent', 'docker pull g'),
            new DoctorVerdict(DoctorCheckId.SANDBOX_IMAGE, DoctorStatus.FAIL, 'missing', 'gnomish image pull'),
            new DoctorVerdict(DoctorCheckId.AGENT_CLI, DoctorStatus.SKIPPED, 'no host stage', null),
        ])

        expect:
        report.text() == 'ok  git version floor: git 2.56.0 meets the floor' + END +
                'warn  guard image: absent; fix: docker pull g' + END +
                'fail  sandbox image: missing; fix: gnomish image pull' + END +
                'skipped  agent CLI binary: no host stage' + END +
                'summary: 1 ok, 1 warn, 1 fail, 1 skipped; fix each fail line, then run gnomish doctor again' + END
        report.failed() == 1
    }

    // UX2: the healthy text rendering
    def "UX2: a healthy run prints only ok lines and the one-line summary, and fails nothing"() {
        given:
        def report = new DoctorReport([
            new DoctorVerdict(DoctorCheckId.FACTORY_VERSION, DoctorStatus.OK, 'gnomish 1.0', null),
            new DoctorVerdict(DoctorCheckId.DOCKER, DoctorStatus.OK, 'reachable', null),
        ])

        expect:
        report.text().readLines() == [
            'ok  factory version: gnomish 1.0',
            'ok  Docker runtime: reachable',
            'summary: 2 ok, 0 warn, 0 fail, 0 skipped; no check failed',
        ]
        report.failed() == 0
    }

    // FR6: warn and skipped never fail; every fail counts
    def "FR6: the failure count is the number of fail results (#statuses)"() {
        given:
        def report = new DoctorReport(statuses.collect {
            new DoctorVerdict(DoctorCheckId.SECRETS, it, 'd', 'f')
        })

        expect:
        report.failed() == failed

        where:
        statuses || failed
        [] || 0
        [
            DoctorStatus.WARN,
            DoctorStatus.SKIPPED,
            DoctorStatus.OK
        ] || 0
        [DoctorStatus.FAIL] || 1
        [
            DoctorStatus.FAIL,
            DoctorStatus.WARN,
            DoctorStatus.FAIL
        ] || 2
    }

    def "FR5: a detail or fix an owner worded over several lines stays one line"() {
        given:
        def report = new DoctorReport([
            new DoctorVerdict(DoctorCheckId.PIPELINE, DoctorStatus.FAIL, 'config.yaml:3: bad\n  stage.yaml:1: worse\n', '  edit\r\nthe law ')
        ])

        expect:
        report.text().readLines()[0] == 'fail  pipeline law: config.yaml:3: bad stage.yaml:1: worse; fix: edit the law'
    }

    // Scenario: cli/operator-doctor / JSON for automation
    def "FR5: the JSON document lists every result with its id, status, detail and fix"() {
        given:
        def report = new DoctorReport([
            new DoctorVerdict(DoctorCheckId.GIT_FLOOR, DoctorStatus.FAIL, 'old', 'upgrade'),
            new DoctorVerdict(DoctorCheckId.LEGACY_LAYOUT, DoctorStatus.WARN, 'leftover', 'remove it'),
            new DoctorVerdict(DoctorCheckId.HOOKS_PATH, DoctorStatus.OK, 'clear', null),
            new DoctorVerdict(DoctorCheckId.DOCKER, DoctorStatus.SKIPPED, 'no box', null),
        ])

        when:
        def json = report.json()
        def tree = new ObjectMapper().readTree(json)

        then: 'one document, ending the line'
        json.endsWith('}' + END)
        tree.get('version').intValue() == 1

        and:
        tree.get('checks').collect {
            [
                it.get('id').textValue(),
                it.get('status').textValue(),
                it.get('detail').textValue(),
                it.get('fix').textValue()
            ]
        } == [
            [
                'git-floor',
                'fail',
                'old',
                'upgrade'
            ],
            [
                'legacy-layout',
                'warn',
                'leftover',
                'remove it'
            ],
            [
                'hooks-path',
                'ok',
                'clear',
                null
            ],
            [
                'docker',
                'skipped',
                'no box',
                null
            ],
        ]
    }

    def "FR5: both renderings spell #status as '#token'"() {
        given:
        def report = new DoctorReport([
            new DoctorVerdict(DoctorCheckId.SECRETS, status, 'd', 'f')
        ])

        expect:
        status.token() == token
        report.text().startsWith("${token}  declared secret: d".toString())
        new ObjectMapper().readTree(report.json()).get('checks').get(0).get('status').textValue() == token

        where:
        status << DoctorStatus.values()
        token << [
            'ok',
            'warn',
            'fail',
            'skipped'
        ]
    }

    def "the report keeps its own copy of the results"() {
        given:
        def verdicts = [
            new DoctorVerdict(DoctorCheckId.DOCKER, DoctorStatus.FAIL, 'down', 'start it')
        ]
        def report = new DoctorReport(verdicts)

        when:
        verdicts.clear()

        then:
        report.failed() == 1
    }
}
