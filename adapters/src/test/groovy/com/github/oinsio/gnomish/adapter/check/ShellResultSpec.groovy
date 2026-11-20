package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.SarifResultReading
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.SarifResult
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR5 of migrate-verify-to-executor-contract: a {@code shell} result leaves its carriers as the
 * executor contract's SARIF result ({@code asSarif}), its details as the result's own details apart
 * from the message (design D19), in the capture's family; the engine reads that result through the one
 * reading of a SARIF result ({@link SarifResultReading}), the details as the finding's own field and
 * the location as the URI with its start line.
 */
class ShellResultSpec extends Specification {

    // The level and location reading moved with the one reading of a SARIF result (SarifResultReadingSpec)
    def "FR5: the engine reads the contract's result through the one SARIF-result reading, the details its own"() {
        given:
        def sarif = new SarifResult(null, SarifLevel.WARNING, UntrustedText.subprocess('m'), UntrustedText.subprocess('a.txt'), 2)

        expect:
        SarifResultReading.toDomain(new ShellResult(sarif, UntrustedText.subprocess('tail')).asSarif()) ==
                new VerdictAnswer.Result(ResultLevel.WARNING, new Finding('m', 'a.txt:2', 'tail'))
        SarifResultReading.toDomain(new ShellResult(sarif, null).asSarif()).finding().details() == null
    }

    def "FR5: a legacy entry keeps its details as the SARIF result's details, and the engine reads them"() {
        given:
        def result = ShellResult.legacy(UntrustedText.subprocess('m'), UntrustedText.subprocess('a.txt:1'),
                UntrustedText.subprocess('why'))

        expect:
        SarifResultReading.toDomain(result.asSarif()) == new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('m', 'a.txt:1', 'why'))
        result.asSarif().message().raw() == 'm'
        result.asSarif().details().raw() == 'why'
        result.asSarif().details().provenance() == Provenance.SUBPROCESS
        result.asSarif().level() == SarifLevel.ERROR
        result.asSarif().uri().raw() == 'a.txt:1'
    }

    def "FR5: a result with #shape details keeps its message and carries no details"() {
        given:
        def head = UntrustedText.factory('command exited with status 2')
        def sarif = new ShellResult(new SarifResult(null, SarifLevel.ERROR, head, null, null), details).asSarif()

        expect:
        sarif.message().is(head)
        sarif.details() == null

        where:
        shape | details
        'no' | null
        'blank' | UntrustedText.subprocess('  \n')
    }

    def "FR5: the synthetic result is an error in the factory's words, the tail as its details"() {
        when:
        def result = ShellResult.synthetic(3, 'tail-line')

        then:
        SarifResultReading.toDomain(result.asSarif()) == new VerdictAnswer.Result(ResultLevel.ERROR,
                new Finding('command exited with status 3', null, 'tail-line'))
        result.sarif().message().provenance() == Provenance.FACTORY
        result.asSarif().message().raw() == 'command exited with status 3'
        result.asSarif().message().provenance() == Provenance.FACTORY
        result.asSarif().details().raw() == 'tail-line'
        result.asSarif().details().provenance() == Provenance.SUBPROCESS
    }

    def "FR5: a relayed SARIF result keeps its rule, level, URI and start line"() {
        given:
        def sarif = new SarifResult(UntrustedText.subprocess('r'), SarifLevel.NOTE, UntrustedText.subprocess('m'),
                UntrustedText.subprocess('src/A.java'), 7)

        expect:
        new ShellResult(sarif, null).asSarif() == sarif
    }
}
