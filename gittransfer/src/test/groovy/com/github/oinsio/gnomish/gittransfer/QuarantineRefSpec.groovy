package com.github.oinsio.gnomish.gittransfer

import spock.lang.Specification

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D18): the harvest quarantine has one spelling,
 * {@code refs/gnomish-quarantine/<task>}, and the harvest's refspec is built beside it — forced into
 * the quarantine, never naming the task branch as its destination.
 */
class QuarantineRefSpec extends Specification {

    def "FR13: a task's quarantine lives under its namespace, and the harvest lands there forced"() {
        when:
        def quarantine = QuarantineRef.forTask('PROJ-42')

        then:
        quarantine.name() == 'refs/gnomish-quarantine/PROJ-42'
        quarantine.name().startsWith(QuarantineRef.NAMESPACE)
        quarantine == new QuarantineRef('refs/gnomish-quarantine/PROJ-42')
        quarantine.harvestRefspec('gnomish/PROJ-42') ==
                new Refspec('+refs/heads/gnomish/PROJ-42:refs/gnomish-quarantine/PROJ-42')
    }

    def "a name outside the namespace or not naming one task is refused: '#name'"() {
        when:
        new QuarantineRef(name)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(reason)

        where:
        name || reason
        'refs/heads/gnomish/T-1' || 'lives under'
        'refs/gnomish-quarantine/' || 'names one task'
        'refs/gnomish-quarantine/  ' || 'names one task'
        'refs/gnomish-quarantine/a/b' || 'names one task'
        'refs/gnomish-quarantine/a:b' || 'names one task'
        'refs/gnomish-quarantine/-x' || 'names one task'
    }

    def "a missing name, task or box branch is refused outright"() {
        when:
        construct()

        then:
        thrown(NullPointerException)

        where:
        construct << [
            { new QuarantineRef(null) },
            { QuarantineRef.forTask(null) },
            { QuarantineRef.forTask('T-1').harvestRefspec(null) },
        ]
    }
}
