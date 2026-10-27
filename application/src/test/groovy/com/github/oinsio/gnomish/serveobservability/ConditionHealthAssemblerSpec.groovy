package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.serve.DaemonConditionsFixture
import com.github.oinsio.gnomish.app.take.DaemonConditionKind
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * NFR-O2, UX4 of add-sandbox-base-image (design D19): the snapshot's {@code conditions} section is
 * one entry per daemon condition, in the set's order with the remote's first, the remote's entry
 * naming its target and every other naming none, each with its condition's live state.
 */
class ConditionHealthAssemblerSpec extends Specification {

    // Scenario: serve/factory-serve / The conditions section carries every condition's entry
    def "one entry per condition, the remote's first, each with its token and state"() {
        given:
        def conditions = DaemonConditionsFixture.closed()
        conditions.report(new TakeResult.OperatorPreconditionUnmet(
                        UntrustedText.factory('image refused; rebuild it'), DaemonConditionKind.IMAGE_CONTRACT))

        when:
        def section = ConditionHealthAssembler.assemble(conditions)

        then:
        section*.condition() == [
            'remote',
            'cloneRefFormat',
            'containerRuntime',
            'imageContract',
            'boxGitFloor'
        ]
        section*.target() == [
            'origin',
            null,
            null,
            null,
            null
        ]
        section*.open() == [
            false,
            false,
            false,
            true,
            false
        ]
        with(section[3]) {
            openSince() != null
            lastError() == 'image refused; rebuild it'
            nextProbeAt() != null
            consecutiveFailures() == 1
        }
    }
}
