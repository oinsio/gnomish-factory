package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.subprocess.Termination
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
        def detail = new GitCommandResult(128, '', HOSTILE).cannotVerifyDetail()

        then: 'one fault stays one line, and no escape sequence drives the operator terminal'
        !detail.contains('\n')
        !detail.contains(ESC)

        and: 'the evidence itself survives — sanitizing is not discarding'
        detail.contains('forged record')
        detail.contains('exit 128')
    }

    def "FR6: a non-exiting invocation names its termination in the same shape"() {
        expect:
        new GitCommandResult(0, '', HOSTILE, Termination.TIMED_OUT)
                .cannotVerifyDetail()
                .contains(Termination.TIMED_OUT.toString())
    }
}
