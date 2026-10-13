package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.git.RoundToken
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D18, single-owner row "The harvest admission"): the
 * one way box content enters the task branch. Against the runner's port: every refusal row — a
 * history rewrite, a service subject, a second parent, an owned path — and every verdict git could
 * not produce leave the branch where it was (no ref transaction runs); a clean range moves the
 * branch alone mid-round, and the branch with its register at a closing harvest, in one
 * transaction; a range already on the branch is admitted again without a move.
 */
class HarvestAdmissionSpec extends Specification {

    static final Path CLONE = Path.of('/clone')
    static final String TASK = 'PROJ-1'
    static final String REGISTER = 'a' * 40
    static final String TIP = 'b' * 40
    static final String TOP = 'c' * 40
    static final String SEP = '\u001f'
    static final AttemptKey KEY = new AttemptKey(TASK, 'implement', 0)
    static final AdmissionRule MID_ROUND = AdmissionRule.midRound(KEY, RoundToken.of(TIP))

    /** A clone whose register, branch tip and harvested top answer, with {@code rows} answering first. */
    private static ScriptedGit clone(@DelegatesTo(ScriptedGit) Closure rows = {}) {
        def git = new ScriptedGit()
        git.with(rows)
        git.answer([
            'rev-parse',
            '--verify',
            '--quiet',
            'refs/gnomish-factory/PROJ-1'
        ], 0, REGISTER + '\n')
        .answer([
            'rev-parse',
            '--verify',
            '--quiet',
            'refs/heads/gnomish/PROJ-1'
        ], 0, TIP + '\n')
        .answer([
            'merge-base',
            '--is-ancestor',
            TOP,
            TIP
        ], 1)
        .answer(['merge-base', '--is-ancestor'], 0)
        .answer([
            'log',
            '--no-color',
            '--format=*'
        ], 0, "${TOP}${SEP}${TIP}${SEP}gnome round\n")
        .answer([
            'log',
            '--no-color',
            '--no-renames'
        ], 0, '')
        .answer(['update-ref', '--stdin'], 0)
    }

    private static AdmittedRange admit(ScriptedGit git, AdmissionRule rule = MID_ROUND) {
        new HarvestAdmission(git, CLONE).admit(TASK, TOP, rule)
    }

    // The refused commits are not lost: they stay in the quarantine until the next harvest, and the
    //     refusal says where, so the operator can inspect what the gnome rewrote.
    def "FR13: a history-rewrite refusal names the quarantine that still holds the harvested commits"() {
        given:
        def git = clone {
            answer([
                'merge-base',
                '--is-ancestor',
                TIP,
                TOP
            ], 1)
        }

        when:
        def admitted = admit(git)

        then:
        admitted instanceof AdmittedRange.Refused
        (admitted as AdmittedRange.Refused).reason().forLog().contains(
                "held at refs/gnomish-quarantine/PROJ-1 in ${CLONE} until the next harvest")
    }

    // Scenario: git/git-task-persistence / A refused mid-round harvest leaves the verdict to the round boundary
    def "FR13: a refused range leaves the branch where it was — #row"() {
        given:
        def git = clone(rows)

        when:
        def admitted = admit(git)

        then:
        admitted instanceof AdmittedRange.Refused
        (admitted as AdmittedRange.Refused).reason().forLog().contains(named)

        and: 'no ref transaction ran, so nothing refused became branch state'
        git.calls(['update-ref']).isEmpty()

        where:
        row | named | rows
        'not a descendant of the branch tip' | 'history rewrite' | {
            answer([
                'merge-base',
                '--is-ancestor',
                TIP,
                TOP
            ], 1)
        }
        'a service subject' | 'gnomish: cleanup' | {
            answer([
                'log',
                '--no-color',
                '--format=*'
            ], 0, "${TOP}${SEP}${TIP}${SEP}gnomish: cleanup\n")
        }
        'a second parent' | 'second parent' | {
            answer([
                'log',
                '--no-color',
                '--format=*'
            ], 0, "${TOP}${SEP}${TIP} ${REGISTER}${SEP}merge\n")
        }
        'an owned path' | '.gnomish-task/state.json' | {
            answer([
                'log',
                '--no-color',
                '--no-renames'
            ], 0, "\u001e${TOP}\n\n.gnomish-task/state.json\n")
        }
    }

    def "FR13: a verdict git could not produce moves nothing — #row"() {
        given:
        def git = clone(rows)

        when:
        def admitted = admit(git)

        then:
        admitted instanceof AdmittedRange.Unverified
        (admitted as AdmittedRange.Unverified).evidence().forLog().contains(says)
        git.calls(['update-ref']).isEmpty()

        where:
        row | says | rows
        'no register' | 'no register here' | {
            answer([
                'rev-parse',
                '--verify',
                '--quiet',
                'refs/gnomish-factory/PROJ-1'
            ], 1, '', 'no register here')
        }
        'no branch' | 'no branch here' | {
            answer([
                'rev-parse',
                '--verify',
                '--quiet',
                'refs/heads/gnomish/PROJ-1'
            ], 1, '', 'no branch here')
        }
        'a failed descent check' | 'bad object' | {
            answer([
                'merge-base',
                '--is-ancestor',
                TIP,
                TOP
            ], 128, '', 'fatal: bad object\n')
        }
        'a failed range inspection' | 'bad object' | {
            answer([
                'log',
                '--no-color',
                '--format=*'
            ], 128, '', 'fatal: bad object\n')
        }
    }

    def "FR13: a refused transaction is unverified, and says so"() {
        given:
        def git = clone {
            answer(['update-ref', '--stdin'], 1, '', 'fatal: cannot lock ref\n')
        }

        when:
        def admitted = admit(git)

        then:
        admitted instanceof AdmittedRange.Unverified
        (admitted as AdmittedRange.Unverified).evidence().forLog().contains('cannot lock ref')
    }

    // Scenario: git/git-task-persistence / Mid-round tip movement in a box is pushed
    def "FR13: a clean mid-round range moves the branch alone, under its compare-and-swap"() {
        given:
        def git = clone()

        when:
        def admitted = admit(git)

        then:
        admitted instanceof AdmittedRange.Admitted
        (admitted as AdmittedRange.Admitted).tip() == TOP
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/PROJ-1 ${TOP} ${TIP}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    // Scenario: git/git-task-persistence / The register follows every closing commit
    def "FR13: a clean closing range lands the branch and the register in one transaction"() {
        given:
        def git = clone {
            answer([
                'log',
                '--no-color',
                '--format=*'
            ], 0, "${TOP}${SEP}${TIP}${SEP}${ServiceCommitMessages.salvage()}\n")
        }

        when:
        def admitted = admit(git, AdmissionRule.salvage())

        then:
        admitted instanceof AdmittedRange.Admitted
        (admitted as AdmittedRange.Admitted).tip() == TOP
        git.calls(['update-ref'])*.argv == [
            [
                'update-ref',
                '--stdin',
                'start',
                "update refs/heads/gnomish/PROJ-1 ${TOP} ${TIP}".toString(),
                "update refs/gnomish-factory/PROJ-1 ${TOP}".toString(),
                'prepare',
                'commit'
            ]
        ]
    }

    def "FR13: a range already on the branch is admitted again without a move or a judgment"() {
        given:
        def git = clone {
            answer([
                'merge-base',
                '--is-ancestor',
                TOP,
                TIP
            ], 0)
        }

        when:
        def admitted = admit(git)

        then:
        admitted instanceof AdmittedRange.Admitted
        (admitted as AdmittedRange.Admitted).tip() == TIP
        git.calls(['update-ref']).isEmpty()
        git.calls(['log']).isEmpty()
    }

    def "FR13: the quarantine is read by its one name, and a quarantine that does not resolve refuses"() {
        given:
        def git = new ScriptedGit()
                .answer([
                    'rev-parse',
                    '--verify',
                    '--quiet',
                    'refs/gnomish-quarantine/PROJ-1'
                ], 0, TOP + '\n')
        def empty = new ScriptedGit().answer(['rev-parse'], 1)

        expect:
        new HarvestAdmission(git, CLONE).quarantined(TASK) == TOP

        when:
        new HarvestAdmission(empty, CLONE).quarantined(TASK)

        then:
        thrown(BranchTipUnavailableException)
    }
}
