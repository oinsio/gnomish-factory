package com.github.oinsio.gnomish.app.port.check

import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.executorcontract.Answer
import com.github.oinsio.gnomish.executorcontract.SarifLevel
import com.github.oinsio.gnomish.executorcontract.SarifResult
import com.github.oinsio.gnomish.executorcontract.SarifRun
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR5, FR6, FR7 of migrate-verify-to-executor-contract (design D18, D19, D23): the one reading of a
 * SARIF result onto the engine's verdict answer — every level to the engine level of its name, the
 * location as the URI with its start line, and the result's own details as the finding's details, the
 * message unchanged — and of a verdict answer's run URL, the first run's that carries one.
 */
class SarifResultReadingSpec extends Specification {

    def "FR7: a #level result reads as the engine's level of its name"() {
        expect:
        SarifResultReading.toDomain(new SarifResult(null, level, UntrustedText.subprocess('m'), null, null)) ==
                new VerdictAnswer.Result(ResultLevel.valueOf(level.name()), new Finding('m', null, null))

        where:
        level << SarifLevel.values()
    }

    def "FR5: the location is the URI, with its start line when the result names one"() {
        expect:
        SarifResultReading.toDomain(new SarifResult(null, SarifLevel.ERROR, UntrustedText.subprocess('m'),
                uri == null ? null : UntrustedText.subprocess(uri), line)).finding().location() == location

        where:
        uri | line || location
        'src/A.java' | 3 || 'src/A.java:3'
        'src/A.java' | null || 'src/A.java'
        null | 3 || null
    }

    def "FR5: a result's properties.details become the finding's details, its message unchanged"() {
        expect:
        SarifResultReading.toDomain(new SarifResult(UntrustedText.subprocess('r1'), SarifLevel.ERROR,
                UntrustedText.subprocess('build red'), null, null, UntrustedText.subprocess('last lines'))) ==
                new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('build red', null, 'last lines'))
    }

    def "FR6: a verdict answer's run URL is the first run's, in the order written, that carries one: #why"() {
        given:
        def verdict = new Answer.Verdict(urls.collect {
            new SarifRun(UntrustedText.subprocess('ci'), [], it == null ? null : UntrustedText.subprocess(it))
        })

        expect:
        SarifResultReading.runUrl(verdict) == expected

        where:
        why | urls || expected
        'no run' | [] || null
        'no run carries one' | [null, null] || null
        'the first carries one' | [
            'https://ci/1',
            'https://ci/2'
        ] || 'https://ci/1'
        'a later run carries one' | [
            null,
            'https://ci/2',
            'https://ci/3'
        ] || 'https://ci/2'
    }
}
