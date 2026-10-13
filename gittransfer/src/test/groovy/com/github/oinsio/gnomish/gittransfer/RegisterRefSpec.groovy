package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13): the factory register's name has one
 * spelling, {@code refs/gnomish-factory/<task>}, and refuses a name it would misplace into a
 * refspec.
 */
class RegisterRefSpec extends Specification {

    def "FR13: a task's register lives under the namespace, its tracking copy under origin's"() {
        when:
        def register = RegisterRef.forTask('PROJ-42')

        then:
        register.name() == 'refs/gnomish-factory/PROJ-42'
        register.name().startsWith(RegisterRef.NAMESPACE)
        register.trackingName() == 'refs/remotes/origin/gnomish-factory/PROJ-42'
        register == new RegisterRef('refs/gnomish-factory/PROJ-42')
    }

    def "a name outside the namespace or not naming one task is refused: '#name'"() {
        when:
        new RegisterRef(name)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(reason)

        where:
        name || reason
        'refs/heads/gnomish/T-1' || 'lives under'
        'refs/gnomish-factory/' || 'names one task'
        'refs/gnomish-factory/  ' || 'names one task'
        'refs/gnomish-factory/a/b' || 'names one task'
        'refs/gnomish-factory/a:b' || 'names one task'
        'refs/gnomish-factory/-x' || 'names one task'
    }

    def "a missing name or task is refused outright"() {
        when:
        construct()

        then:
        thrown(NullPointerException)

        where:
        construct << [
            { new RegisterRef(null) },
            { RegisterRef.forTask(null) }
        ]
    }
}
