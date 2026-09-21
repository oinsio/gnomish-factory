package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
import com.github.oinsio.gnomish.untrustedtext.TextSafety
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR6 of harden-logging-observability: {@code cannotVerifyDetail} carries git's own stderr into a
 * {@code GitPersistFailedException} message, and that message is rendered into a log record and
 * into the escalation report. Subprocess output is attacker-influenced, so it is sanitized where
 * it is put into the message — the log-call gate cannot see inside an exception's text
 * ({@code .claude/rules/logging.md}, "untrusted text in exception messages").
 */
class GitCommandResultDetailSpec extends Specification {

    static final String ESC = Character.toString(27)
    static final String HOSTILE = "fatal: bad object\n2026-01-01 ERROR forged record${ESC}[31m"

    def "FR6: the cannot-verify detail is flattened and stripped"() {
        when:
        def detail = GitCommandResult.of(128, '', HOSTILE).cannotVerifyDetail()

        then: 'one fault stays one line, and no escape sequence drives the operator terminal'
        !detail.contains('\n')
        !detail.contains(ESC)

        and: 'the evidence itself survives — sanitizing is not discarding'
        detail.contains('forged record')
        detail.contains('exit 128')
    }

    def "FR6: a non-exiting invocation names its termination in the same shape"() {
        expect:
        GitCommandResult.of(0, '', HOSTILE, Termination.TIMED_OUT)
                .cannotVerifyDetail()
                .contains(Termination.TIMED_OUT.toString())
    }

    // FR5, FR9 of add-base-ref-resolution (design D6 of bound-subprocess-commands): an invocation
    // that never reached its own exit established no remote outcome, so failureDetail names how it
    // ended and withholds both the exit code and git's words -- reading either as a verdict is how
    // a fabricated refusal reached an operator.
    def "FR5: a failure detail for a non-exiting invocation withholds the exit code and git's words"() {
        given:
        def detail = GitCommandResult.of(128, '', HOSTILE, termination).failureDetail('fetch')

        expect:
        detail.contains(named)

        and: 'neither the exit code nor the stderr is offered as git\'s verdict'
        !detail.contains('128')
        !detail.contains('forged record')

        where:
        termination || named
        Termination.TIMED_OUT || 'the fetch timed out'
        Termination.INTERRUPTED || 'the fetch was interrupted'
    }

    // NFR-S2 of fix-lifecycle-push (task 12.3 of add-base-ref-resolution): both details rest on
    // GitProcessRunner scrubbing stderr at capture, and both scrub again where the text enters a
    // message — a result built anywhere else (a spec, a future runner) cannot leak a token
    // through one detail and not the other.
    def "NFR-S2: a credential-bearing URL in stderr is masked in both details"() {
        given:
        def result = GitCommandResult.of(128, '',
                'fatal: unable to access https://ghp_SECRETTOKEN@github.com/owner/repo.git/: timed out')

        expect:
        [
            result.cannotVerifyDetail(),
            result.failureDetail('fetch')
        ].every {
            !it.contains('ghp_SECRETTOKEN') && it.contains('https://***@github.com/owner/repo.git')
        }
    }

    // FR5, FR9 of add-base-ref-resolution: both details below are quoted inside a caller's own
    // prose -- CommitBaseFetch and RefreshedTip explain the refusal, TaskBranchLocator names what
    // origin said, GitPersistFailedException names the round. The log exit keeps the tail, so a
    // detail that filled the log cap by itself would push every one of those openings, and its
    // own, out of the record and leave nothing but git's words.
    private static final String LONG_STDERR = 'x' * 20_000

    private static String quotedInProse(String detail) {
        UntrustedText.factory('origin carries the branch but the fetch did not deliver it: ' + detail + '.').forLog()
    }

    def "FR5: a long capture leaves the failureDetail sentence room inside the log cap"() {
        given:
        def detail = GitCommandResult.of(128, '', LONG_STDERR).failureDetail('commit fetch')

        expect: 'the detail alone is well inside the log cap, so quoting it does not overflow'
        detail.length() <TextSafety.DEFAULT_CAP_CHARS

        when:
        def rendered = quotedInProse(detail.forLog())

        then: 'the caller opening, the detail opening and the git evidence all survive the exit'
        rendered.startsWith('origin carries the branch')
        rendered.contains('the commit fetch exited 128')
        rendered.contains('xxxx')
    }

    // M7 of fix-envelope-medium (FR10): the headroom STDERR_CAP_CHARS reserves under the log cap
    //     was reserved against the *input* to the flattening. A stderr of nothing but line
    //     separators renders six characters per one, fills the log cap by itself, and the exit's
    //     tail-keeping cut then drops exactly the head this constant exists to protect.
    def "M7: a stderr that expands when rendered still leaves the detail's opening in the record"() {
        given: 'a capture of nothing but line separators, at the detail\'s own bound'
        def separators = Character.toString(0x2028) * 1_400

        when:
        def rendered = GitCommandResult.of(128, '', separators).failureDetail('fetch').forLog()

        then: 'the sentence still names what failed, and git\'s words are still quoted'
        rendered.contains('the fetch exited 128')
        rendered.contains('\\u2028')

        and: 'the detail alone still fits inside the log cap, so a caller may quote it in prose'
        GitCommandResult.of(128, '', separators).failureDetail('fetch').length() <TextSafety.DEFAULT_CAP_CHARS
    }

    def "FR6: the same headroom holds for the cannot-verify detail"() {
        given:
        def detail = GitCommandResult.of(128, '', LONG_STDERR).cannotVerifyDetail()

        expect:
        detail.length() <TextSafety.DEFAULT_CAP_CHARS

        when:
        def rendered = quotedInProse(detail.forLog())

        then:
        rendered.startsWith('origin carries the branch')
        rendered.contains('the boundary could not be verified')
        rendered.contains('xxxx')
    }
}
