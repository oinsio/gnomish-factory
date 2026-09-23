package com.github.oinsio.gnomish.app.port.git

import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR10, UX2 of own-git-transfer-argv: the floor refusal reads as one precondition sentence — the
 * requirement first, then what the installed git did, then why the floor is where it is — for
 * both arms, a version below the floor and no version at all. The reporter prints this message
 * and nothing else, so its wording is the operator's whole explanation.
 */
class GitVersionRefusedExceptionSpec extends Specification {

    static final String REASON = 'the seed clone relies on it'

    def "FR10, UX2: a version below the floor is reported as a requirement, the installed version and the reason"() {
        expect:
        new GitVersionRefusedException('2.45.1', '2.44.0', REASON).message ==
                'gnomish requires git 2.45.1 or newer: the installed git is 2.44.0; ' + REASON
    }

    def "FR10, UX2: an unreported version is reported as a requirement, what git printed and the reason"() {
        expect:
        new GitVersionRefusedException('2.45.1', UntrustedText.subprocess('git: command not found'), REASON).message ==
                'gnomish requires git 2.45.1 or newer: the installed git did not report a version ' +
                '(git --version printed: git: command not found); ' + REASON
    }
}
