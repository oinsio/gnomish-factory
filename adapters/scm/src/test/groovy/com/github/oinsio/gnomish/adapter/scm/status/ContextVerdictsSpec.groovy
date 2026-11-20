package com.github.oinsio.gnomish.adapter.scm.status

import static com.github.oinsio.gnomish.adapter.scm.status.ScmContext.Source.CHECK_RUN
import static com.github.oinsio.gnomish.adapter.scm.status.ScmContext.Source.STATUS
import static com.github.oinsio.gnomish.adapter.scm.status.ScmContextState.FAILED
import static com.github.oinsio.gnomish.adapter.scm.status.ScmContextState.PASSED
import static com.github.oinsio.gnomish.adapter.scm.status.ScmContextState.RUNNING

import spock.lang.Specification

/**
 * FR4, NFR-R2 of migrate-verify-to-executor-contract: latest per context within one listing by the
 * platform's sequence, the first listed winning a tie; across the two listings the state further
 * from a pass, the commit status winning a tie.
 */
class ContextVerdictsSpec extends Specification {

    private static ScmContext report(ScmContext.Source source, ScmContextState state, long sequence, String detail) {
        new ScmContext(source, 'build', state, sequence, detail)
    }

    def "within one listing the larger sequence wins, and on a tie the first listed: #why"() {
        expect:
        ContextVerdicts.of(reports, 0).of('build').get().detail() == winner

        where:
        why | reports || winner
        'later listed, larger' | [
            report(STATUS, FAILED, 1, 'old'),
            report(STATUS, PASSED, 2, 'new')
        ] || 'new'
        'first listed, larger' | [
            report(STATUS, PASSED, 2, 'new'),
            report(STATUS, FAILED, 1, 'old')
        ] || 'new'
        'a tie' | [
            report(STATUS, FAILED, 1, 'first'),
            report(STATUS, PASSED, 1, 'second')
        ] || 'first'
        'a check-run tie' | [
            report(CHECK_RUN, FAILED, 1, 'first'),
            report(CHECK_RUN, PASSED, 1, 'second')
        ] || 'first'
    }

    def "across the listings the state further from a pass wins, the commit status on a tie: #why"() {
        expect:
        ContextVerdicts.of([
            report(STATUS, statusState, 9, 'status'),
            report(CHECK_RUN, runState, 1, 'run')
        ], 0)
        .of('build').get().detail() == winner

        where:
        why | statusState | runState || winner
        'the run failed' | PASSED | FAILED || 'run'
        'the status failed' | FAILED | PASSED || 'status'
        'the run is running' | FAILED | RUNNING || 'run'
        'both failed' | FAILED | FAILED || 'status'
    }

    def "a context no listing reports is absent"() {
        expect:
        ContextVerdicts.of([
            report(STATUS, PASSED, 1, 'x')
        ], 0).of('lint').isEmpty()
    }
}
