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
 * which words were whose; the reports now take carriers and render them through the comment plane,
 * which keeps line structure and length while stripping escapes and breaking mentions and issue
 * references. Which of the plane's two shapes a field takes is decided by what a fence would be
 * describing (design D6, revised 2026-09-19): a report of the factory's own prose — the "Task X is
 * parked" head, the field headings, the shared remedy trailer — quotes each untrusted field
 * through the inline shape, and the labeled fence stays for a block that really is machine output
 * end to end. Fencing the report wholesale at {@code Tracker.park} remains what design D7 rejects;
 * a fence per field is the same claim made once per field, which the headings already make.
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

    /**
     * The fence's label. Asserted <em>absent</em> in the two reports below: both are the factory's
     * own prose with untrusted fields quoted into it, and such a field takes the comment plane's
     * inline shape. It is still asserted present for {@code refused}, whose detail is one capture
     * under a heading — a block the label describes truthfully.
     */
    private static final String LABEL = 'Untrusted machine output:'

    def "the underdetermined report renders every designator value it names inert"() {
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

        and: 'each value is stripped and inert, under the one heading that names them all'
        // Design D6, revised 2026-09-19: the inline shape, not one labeled fence per value. A
        // fence claims that everything between its markers is machine output; the "Values named:"
        // heading already says that about the list, and n fences repeat it n times inside a report
        // that is the factory's own prose end to end.
        report.count(LABEL) == 0
        !report.contains('~~~')
        report.contains('Values named:')

        and: 'and a mention or an issue reference inside one can neither ping nor cross-link'
        !report.contains('@team')
        !report.contains('#123')

        and: 'the factory-authored head and trailer read as the factory\'s own lines'
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

        and: 'each located error leaves the loader through the comment plane — kept whole, stripped\n' +
        'and mention-broken, so an injected line reads as data rather than as a record of its\n' +
        'own; inline rather than fenced, since the "Located errors (n):" heading is what\n' +
        'attributes them and n fences would only repeat it (design D6 revised 2026-09-19, D10)'
        report.count(LABEL) == 0
        !report.contains('~~~')
        report.contains('unknown type')

        and: 'and the base ref, held to the ref-name grammar, is named as the factory\'s own line'
        report.contains('Base ref: release/1.18')
    }

    /** A hostile value: a cursor-driving CSI sequence appended to an otherwise ordinary name. */
    private static String hostile(String base) {
        (base + ESC + '[2J').toString()
    }
}
