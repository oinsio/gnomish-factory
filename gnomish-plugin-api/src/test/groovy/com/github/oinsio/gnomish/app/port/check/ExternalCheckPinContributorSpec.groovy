package com.github.oinsio.gnomish.app.port.check

import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.time.Duration
import spock.lang.Specification

/**
 * FR16 (design D10) of add-sandbox-core: the adapter half of the pin-check contract. This spec
 * covers the port's own {@link ExternalCheckPinContributor#none} default — the contribution of an
 * adapter with no repo-borne definition (the interactive human oracle), which contributes nothing
 * for any check so that, with nothing declared in the stage law either, the pin passes vacuously.
 *
 * Added by task 8.7 of split-into-modules (design D13(c)).
 */
class ExternalCheckPinContributorSpec extends Specification {

    private static VerifyCheck.External check(String checkId) {
        new VerifyCheck.External(checkId, 'sample', Duration.ofSeconds(10), Duration.ofMinutes(5), VerifyCheck.TimeoutClass.QUALITY)
    }

    // FR16: none() is a real contributor, not null — callers union its contribution unconditionally.
    def "none() contributes an empty pin set rather than nothing at all"() {
        when:
        def contributor = ExternalCheckPinContributor.none()

        then:
        contributor != null
        contributor.pinPaths(check('ci')) == [] as Set
    }

    // FR16: the empty contribution is unconditional — an adapter with no repo-borne definition
    // contributes nothing whichever check it is asked about.
    def "none() contributes nothing whichever check it is asked about"() {
        given:
        def contributor = ExternalCheckPinContributor.none()

        expect:
        contributor.pinPaths(check(checkId)).isEmpty()

        where:
        checkId << ['ci', 'lint', 'human-review']
    }
}
