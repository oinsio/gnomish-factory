package com.github.oinsio.gnomish.app.take

import com.github.oinsio.gnomish.baseref.UnderdeterminedCause
import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * Design D6, D7 of type-untrusted-text (originally FR6 of harden-logging-observability): the three
 * base reports assemble text the factory did not author — designator values a tracker held, the
 * resolver's account built around them, git's own words about a refused refresh, loader errors
 * quoting the target repository's own {@code .gnomish/}.
 *
 * <p>What changed at task 6.1 is not that the reports neutralize — they always did — but
 * <em>which</em> exit they neutralize through and where the boundary falls. The per-field
 * {@code LogText.forLog} calls flattened everything to one capped line and left nothing to say
 * which words were whose; the reports now take carriers and render them through the comment exit,
 * so each untrusted part lands in a labeled fence with mentions and issue references broken, and
 * the factory's own lines — the "Task X is parked" head and the shared remedy trailer — stay
 * outside it. That division is the whole reason the report is assembled here rather than fenced
 * wholesale at {@code Tracker.park} (design D7's rejected alternative).
 *
 * <p>The ref names the reports interpolate are deliberately <em>not</em> carriers: every ref
 * entering the process is held to {@code RefNameSyntax} first — the resolver for a fresh claim,
 * {@code PinnedRefGate} at the {@code task.json} read for a resume — so they are NG4 values and an
 * escape sequence cannot reach them.
 */
class BaseReportSanitizingSpec extends Specification {

    /** ESC, built rather than written: a literal one cannot survive a source file or a diff. */
    private static final String ESC = Character.toString(27 as char)

    private static final String FORGED = '\nWARN forged log record'

    private static final String LABEL = 'Untrusted machine output:'

    def "the underdetermined report fences every designator value it names"() {
        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1',
                UnderdeterminedCause.DESIGNATOR_CONFLICT,
                [
                    UntrustedText.tracker(hostile('main')),
                    UntrustedText.tracker('release/9' + FORGED + ' @team #123')
                ],
                'two values on one task')

        then: 'no escape sequence survives anywhere in the report'
        !report.contains(ESC)

        and: 'each value is fenced and labeled, so an injected line reads as data'
        report.count(LABEL) == 2
        report.contains('~~~')

        and: 'and a mention or an issue reference inside one can neither ping nor cross-link'
        !report.contains('@team')
        !report.contains('#123')

        and: 'the factory-authored head and trailer stay outside every fence'
        report.startsWith('Task PROJ-1 is parked')
        report.endsWith(ParkedBaseTrailer.withRemedy("Fix the task's base designator or the project's"
                + ' allowed bases, then return the task to work.'))
    }

    // The resolver's sentence is factory prose whose quoted values entered it through the
    //     carrier's own toString() — the log exit — so it is inert with no call of its own here.
    def "the underdetermined report carries the resolver's reason without a second rendering"() {
        given: 'the sentence BaseDesignator builds, with the hostile label already quoted into it'
        def label = UntrustedText.tracker(hostile('x') + FORGED)
        def reason = "the task names base '${label}', which no configured allowed base accepts".toString()

        when:
        def report = FreshClaimBaseReport.underdetermined(
                'PROJ-1', UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED, [], reason)

        then:
        !report.contains(ESC)
        !report.contains(FORGED)
    }

    def "the refused report fences the refresh detail and leaves the resolved ref plain"() {
        when:
        def report = FreshClaimBaseReport.refused(
                'PROJ-1', 'release/1.18', UntrustedText.subprocess(hostile('fetch said no') + FORGED))

        then:
        !report.contains(ESC)

        and: 'git\'s own words are inside the one labeled fence'
        report.count(LABEL) == 1
        report.contains('fetch said no')

        and: 'and the ref name, which passed the ref-name grammar, is named outside it'
        report.contains('Resolved ref: release/1.18')
    }

    def "the resume report fences the resolution detail and leaves the pinned ref plain"() {
        when:
        def report = ResumeBaseReport.unresolved(
                'PROJ-1', 'release/1.18', UntrustedText.subprocess(hostile('gone') + FORGED))

        then:
        !report.contains(ESC)
        report.count(LABEL) == 1
        report.contains('Pinned ref: release/1.18')
    }

    def "the law report fences every located error and leaves the base ref plain"() {
        given:
        def errors = [
            new ConfigError('config.yaml', 'task-branch.base.type', hostile('unknown type') + FORGED)
        ]

        when:
        def report = BaseLawReport.of('PROJ-1', 'release/1.18', ObjectId.of('a' * 40), errors)

        then: 'no escape sequence survives anywhere in the report'
        !report.contains(ESC)

        and: 'each located error leaves the loader through the comment exit — kept whole, but inside\n' +
        'a labeled fenced block, so an injected line reads as data rather than as a record of\n' +
        'its own (design D6, D10 of type-untrusted-text)'
        report.count(LABEL) == 1
        report.contains('~~~')
        report.contains('unknown type')

        and: 'and the base ref, held to the ref-name grammar, is named outside the fence'
        report.contains('Base ref: release/1.18')
    }

    /** A hostile value: a cursor-driving CSI sequence appended to an otherwise ordinary name. */
    private static String hostile(String base) {
        (base + ESC + '[2J').toString()
    }
}
