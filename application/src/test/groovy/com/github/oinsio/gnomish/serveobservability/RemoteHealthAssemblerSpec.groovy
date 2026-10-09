package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.ServeProperties
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.serve.RemoteOutageGates
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification

/**
 * NFR-O3, UX6 of add-base-ref-resolution: {@link RemoteHealthAssembler#assemble} builds the
 * snapshot's {@code remote} section from whatever gates the daemon runs — an empty section when
 * there are none, one entry per gate keyed by its own target when there are.
 */
class RemoteHealthAssemblerSpec extends Specification {

    def "no gates assembles an empty remote section"() {
        expect:
        RemoteHealthAssembler.assemble([]).isEmpty()
    }

    def "one gate assembles one entry, keyed and populated from its own health"() {
        given:
        def gate = RemoteOutageGates.forServe(BaseRefGit.UNWIRED, Path.of('.'), new ServeProperties(0, null, null, null, null, null, null, null, null), new VirtualClock(), {}, { ignored -> })

        when:
        def result = RemoteHealthAssembler.assemble([gate])

        then:
        result.keySet() == ['origin'] as Set
        result['origin'].target() == 'origin'
        !result['origin'].open()
    }
}
