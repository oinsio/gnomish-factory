package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.subprocess.Termination
import spock.lang.Specification

/**
 * FR13 of harden-logging-observability: a tip resolution answers with a SHA and nothing else, so
 * every failure shape reduces to the empty string unless the invocation itself is checked. This is
 * the one place that check lives — {@link VerifiedTip#read} for probes that may skip an
 * observation, {@link VerifiedTip#required} for resolutions recorded durably or gating a
 * decision.
 */
class VerifiedTipSpec extends Specification {

    static final String SHA = '1f2e3d4c5b6a798877665544332211000ffeeddc'

    def "FR13: only a zero-exit resolution that printed a ref is a tip"() {
        expect:
        VerifiedTip.read(result) == expected

        where:
        result || expected
        new GitCommandResult(0, SHA + '\n', '') || Optional.of(SHA)
        new GitCommandResult(128, '', 'fatal: bad revision') || Optional.empty()
        new GitCommandResult(0, '', '') || Optional.empty()
        new GitCommandResult(0, '   \n', '') || Optional.empty()
        new GitCommandResult(0, SHA, '', Termination.INTERRUPTED) || Optional.empty()
    }

    def "FR13: a durable resolution refuses a blank tip and carries the git evidence"() {
        when:
        VerifiedTip.required('refs/heads/gnomish/PROJ-1', 'rev-parse', new GitCommandResult(128, '', 'fatal: bad revision'))

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains('refs/heads/gnomish/PROJ-1')
        failure.message.contains('exited 128')
        failure.message.contains('fatal: bad revision')
    }

    def "FR13: a resolution that never ran to its own exit reports how it ended instead"() {
        when:
        VerifiedTip.required('HEAD', 'rev-parse', new GitCommandResult(0, '', '', Termination.INTERRUPTED))

        then:
        def failure = thrown(BranchTipUnavailableException)
        failure.message.contains('did not run to its own exit')
        failure.message.contains('INTERRUPTED')
    }

    def "FR13: a clean resolution returns the trimmed sha"() {
        expect:
        VerifiedTip.required('HEAD', 'rev-parse', new GitCommandResult(0, SHA + '\n', '')) == SHA
    }
}
