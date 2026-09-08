package com.github.oinsio.gnomish.app.port.git

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR5, FR6, FR9 of add-base-ref-resolution: {@link BaseRefGit#UNWIRED} fails closed — every call
 * refuses with an {@link IllegalStateException} naming the call, rather than a guessed default.
 * None of the claim-chain port-fake specs exercises {@code UNWIRED} directly (they replace this
 * capability with a working stub instead), so this is the one place {@code Unwired} itself is
 * driven end to end.
 */
class BaseRefGitUnwiredSpec extends Specification {

    private static final Path DIR = Path.of('.')

    def "discoverDefaultBranch refuses, naming itself"() {
        when:
        BaseRefGit.UNWIRED.discoverDefaultBranch(DIR)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('discoverDefaultBranch')
    }

    def "refresh refuses, naming itself"() {
        when:
        BaseRefGit.UNWIRED.refresh(DIR, 'main')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('refresh')
    }

    def "resolveForResume refuses, naming itself"() {
        when:
        BaseRefGit.UNWIRED.resolveForResume(DIR, 'main')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('resolveForResume')
    }

    def "probe refuses, naming itself"() {
        when:
        BaseRefGit.UNWIRED.probe(DIR)

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('probe')
    }
}
