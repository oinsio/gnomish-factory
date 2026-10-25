package com.github.oinsio.gnomish.sandbox.environment

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.app.BaseTag
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR5, NFR-O1, UX2 of add-sandbox-base-image (design D3): the judgement of one image's facts and
 * the fix the refusal ends with. The materialize that calls it is {@link BoxImageContractSpec}'s
 * subject; here the facts are built directly. The check logs nothing (design D19): the layer that
 * decides on the refusal writes the one line.
 */
class ImageContractCheckSpec extends Specification {

    static final ImageContract CONTRACT = ImageContract.current()

    private LogCaptureSupport logs

    def setup() {
        logs = LogCaptureSupport.attach(ImageContractCheck, Level.INFO)
    }

    def cleanup() {
        logs.detach()
    }

    private static ImageFacts labelled(String value) {
        new ImageFacts('gnomish/img', [], [(ImageContract.LABEL): UntrustedText.subprocess(value)])
    }

    def "FR5: a label other than the required version, spelled exactly, is refused: '#value'"() {
        given:
        def check = new ImageContractCheck(CONTRACT, Optional.of(new BaseTag('1.2')))

        when:
        check.require(labelled(value))

        then:
        thrown(ImageContractRefusedException)

        and: 'D19: the refusal is the message; the check writes no line of its own'
        logs.list.isEmpty()

        where:
        value << [
            "${CONTRACT.contractVersion() + 1}",
            " ${CONTRACT.contractVersion()}",
            "0${CONTRACT.contractVersion()}",
            ''
        ]
    }

    def "FR5: the running factory's check requires the contract it was built with"() {
        when:
        ImageContractCheck.current().require(labelled("${CONTRACT.contractVersion()}"))

        then:
        noExceptionThrown()
        logs.list.isEmpty()
    }

    def "UX2: a release build's fix is the FROM line of its base tag, taken whole: #tag"() {
        expect:
        ImageContractCheck.remedy(Optional.of(new BaseTag(tag))) == 'rebuild: FROM ghcr.io/oinsio/gnomish-sandbox-base:' + tag

        where: 'X.Y of a final release, the full version of a pre-release'
        tag << [
            '0.1',
            '1.12',
            '0.4.1-rc.1',
            '1.0.0-beta.2'
        ]
    }

    def "FR5: a development build, which has no base tag, names the base built from this checkout instead"() {
        when:
        def remedy = ImageContractCheck.remedy(Optional.empty())

        then:
        remedy == "rebuild FROM a base built from this checkout's sandbox-image/Dockerfile" +
                ' (a development build: no published base)'
    }

    def "NFR-O1: the refusal carries the image author's label through the log exit"() {
        given:
        def check = new ImageContractCheck(CONTRACT, Optional.empty())
        def hostile = "1\n${Character.toString(27)}[2Jforged"

        when:
        check.require(labelled(hostile))

        then:
        def refused = thrown(ImageContractRefusedException)
        refused.message.contains(ImageContract.LABEL + '=' + UntrustedText.subprocess(hostile).forLog())
        !refused.message.contains('\n')
        refused.message.endsWith(ImageContractCheck.remedy(Optional.empty()))
        logs.list.isEmpty()
    }
}
