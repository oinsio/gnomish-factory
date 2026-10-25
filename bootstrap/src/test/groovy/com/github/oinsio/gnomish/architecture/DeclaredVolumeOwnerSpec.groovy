package com.github.oinsio.gnomish.architecture

import com.github.oinsio.gnomish.testsupport.RepoSourceTree
import java.util.regex.Pattern
import spock.lang.Specification

/**
 * FR1, FR2 of fix-image-declared-volumes: the ephemeral overrides a factory container runs with
 * come only from the image's own declaration, read when the container is created. One producer
 * builds an override set — {@code DeclaredVolumeOverrides.resolve}, which takes the image's facts
 * from its one inspection ({@code ImageFacts}, design D3 of add-sandbox-base-image) — and it is
 * called where a box or a guard is created; no production source names the
 * declared path of any particular image (the default guard image's {@code VOLUME} is the one a
 * hand-written list would reach for first).
 *
 * <p>Every allowlisted file must still match, so a rename cannot leave the gate naming nothing.
 */
class DeclaredVolumeOwnerSpec extends Specification {

    private static final String ENV = 'sandbox/docker/src/main/java/com/github/oinsio/gnomish/sandbox/environment/'

    /** The default guard image's declared path, the one an override list would be tempted to name. */
    static final String MITMPROXY_DECLARED = '/home/mitmproxy/.mitmproxy'

    private static Map<String, String> productionCode() {
        def sources = RepoSourceTree.productionSources()
        assert sources.size() >= RepoSourceTree.KNOWN_PRODUCTION_SOURCES
        sources.collectEntries {
            [(RepoSourceTree.relative(it)): RepoSourceTree.code(it)]
        }
    }

    private static List<String> namers(Map<String, String> code, String pattern) {
        code.findAll { path, text -> text =~ pattern }.keySet().sort()
    }

    // Scenario: sandbox/execution-environment / No image's declared path is named in production code
    def "FR1: an override set is built only from the image read, at box and guard creation, and names no image's path"() {
        given:
        def code = productionCode()

        expect: 'the record is constructed only inside its own resolve, which reads the image'
        namers(code, /new\s+DeclaredVolumeOverrides\s*\(/) == [
            ENV + 'DeclaredVolumeOverrides.java'
        ]

        and: 'resolve is called where the task container and the guard are created, and nowhere else'
        namers(code, /DeclaredVolumeOverrides\s*\.\s*resolve\s*\(/) == [
            ENV + 'ContainerMaterializer.java',
            ENV + 'EgressGuard.java'
        ]

        and: 'no production source spells a particular image\'s declared path'
        namers(code, Pattern.quote(MITMPROXY_DECLARED)) == []
    }
}
