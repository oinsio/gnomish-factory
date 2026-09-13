package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.baseref.UnderdeterminedCause
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.gitobjects.ObjectId
import spock.lang.Specification

/**
 * FR6 of harden-logging-observability: the three base reports assemble text the factory did not
 * author — designator values a tracker held, ref names read back from {@code task.json} or reported
 * by {@code origin}, loader errors quoting the target repository's own {@code .gnomish/}. The
 * assembled report is logged whole by {@code SlotOutcomeLog} and posted as a tracker comment, and
 * no untrusted accessor is left at those sinks for {@code UntrustedLogTextGateSpec} to see — so
 * each report neutralizes its own untrusted fields, the same remedy {@code
 * GitCommandResult.failureDetail} applies one layer down.
 */
class BaseReportSanitizingSpec extends Specification {

    /** ESC, built rather than written: a literal one cannot survive a source file or a diff. */
    private static final String ESC = Character.toString(27 as char)

    private static final String FORGED = '\nWARN forged log record'

    def "the underdetermined report neutralizes the designator values"() {
        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1',
                UnderdeterminedCause.DESIGNATOR_CONFLICT,
                [
                    hostile('main'),
                    'release/9' + FORGED
                ],
                'two values on one task')

        then:
        inert(report)
    }

    def "the underdetermined report neutralizes the resolver's reason"() {
        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1',
                UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED,
                [],
                "the task names base '" + hostile('x') + "'" + FORGED)

        then:
        inert(report)
    }

    def "the refused report neutralizes the resolved ref and the refresh detail"() {
        when:
        def report = FreshClaimBaseReport.refused('PROJ-1', hostile('main') + FORGED, hostile('fetch said no') + FORGED)

        then:
        inert(report)
    }

    def "the resume report neutralizes the pinned ref and the resolution detail"() {
        when:
        def report = ResumeBaseReport.unresolved('PROJ-1', hostile('main') + FORGED, hostile('gone') + FORGED)

        then:
        inert(report)
    }

    def "the law report neutralizes the base ref and every located error"() {
        given:
        def errors = [
            new ConfigError('config.yaml', 'task-branch.base.type', hostile('unknown type') + FORGED)
        ]

        when:
        def report = BaseLawReport.of('PROJ-1', hostile('main') + FORGED, ObjectId.of('a' * 40), errors)

        then:
        inert(report)
    }

    /** A hostile value: a cursor-driving CSI sequence appended to an otherwise ordinary name. */
    private static String hostile(String base) {
        (base + ESC + '[2J').toString()
    }

    /**
     * The report carries no escape sequence and no forged record: the report's own structure keeps
     * its line breaks, so the check is for the injected line, not for newlines as such.
     */
    private static boolean inert(String report) {
        assert !report.contains(ESC)
        assert !report.contains(FORGED)
        true
    }
}
