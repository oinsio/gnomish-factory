package com.github.oinsio.gnomish.app

import java.nio.file.Path
import spock.lang.Specification

/**
 * FR4, FR13 of add-factory-serve: the serve arguments become the run order every slot of the
 * daemon works to, in one place (D1 of introduce-take-order) — the {@code --dir} clone, the
 * startup definition, no {@code --base}, never interactive, always salvaging.
 */
class ServeArgumentsSpec extends Specification implements RunChainFakes {

    def "FR13: the slots' run order is the serve clone, non-interactive, with no base, salvaging"() {
        given:
        def definition = pipeline()

        when:
        def order = new ServeArguments(Path.of('/work/widgets'), 3, true).slotRunOrder(definition)

        then:
        order == new RunOrder(Path.of('/work/widgets'), null, definition, false)
    }
}
