package com.github.oinsio.gnomish.app.describe

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.github.oinsio.gnomish.executorcontract.Capability
import com.github.oinsio.gnomish.executorcontract.Describe
import com.github.oinsio.gnomish.executorcontract.EnforcementLevel
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * {@link QuietStdoutBounds} (FR16 of extract-agent-round-runner; design D6, "The silence bound follows
 * the dialect's declaration"): the bounds on a quiet stdout apply exactly when the describe declares
 * {@code streams_progress}, whatever else it declares.
 */
class QuietStdoutBoundsSpec extends Specification {

    def "FR16: a describe declaring #capabilities answers #applies"() {
        expect:
        QuietStdoutBounds.applyTo(describe(capabilities)) == applies

        where:
        capabilities || applies
        [] || false
        [
            Capability.POLL,
            Capability.CANCEL,
            Capability.RESUMABLE
        ] || false
        [Capability.STREAMS_PROGRESS] || true
        [
            Capability.CANCEL,
            Capability.STREAMS_PROGRESS
        ] || true
    }

    private static Describe describe(List<Capability> capabilities) {
        new Describe(1, UntrustedText.subprocess('fake-1'), [Role.TRANSFORM], capabilities,
        JsonNodeFactory.instance.objectNode(), [], [], null, [], EnforcementLevel.PROMPT_ONLY, true, false)
    }
}
