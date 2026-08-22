package com.github.oinsio.gnomish.sandbox.environment

import spock.lang.Specification

/**
 * FR2 of add-serve-sandbox-lifecycle: {@code ownershipLabelArgs} is the single seam every
 * creation command splices its label flags from, so a factory object with a partial label set
 * (e.g. factory + task but no mode/project) is impossible by construction — there is no other
 * code path that builds {@code --label} flags. Also covers {@link ObjectOwnership}'s own
 * blank-projectId guard.
 */
class FactoryDockerLabelsSpec extends Specification {

    def "FR2: ownershipLabelArgs carries exactly the four ownership labels, in order"() {
        given:
        def ownership = new ObjectOwnership(OwnershipMode.TRACKED, 'proj-1')

        expect:
        FactoryDockerLabels.ownershipLabelArgs('k1', ownership) == [
            '--label',
            'com.github.oinsio.gnomish.factory=true',
            '--label',
            'com.github.oinsio.gnomish.task=k1',
            '--label',
            'com.github.oinsio.gnomish.mode=tracked',
            '--label',
            'com.github.oinsio.gnomish.project=proj-1',
        ]
    }

    def "FR2: the manual mode assigns the manual label value"() {
        expect:
        FactoryDockerLabels.ownershipLabelArgs('k1', new ObjectOwnership(OwnershipMode.MANUAL, 'proj-1'))
                .containsAll([
                    '--label',
                    'com.github.oinsio.gnomish.mode=manual'
                ])
    }

    def "NFR-S1 of add-serve-sandbox-lifecycle: label values come only from the key, the mode and the project id"() {
        given: 'the sanitized environment key, the mode, and a digest-shaped project identity'
        def ownership = new ObjectOwnership(OwnershipMode.TRACKED, 'a1b2c3d4e5f6')

        when:
        def args = FactoryDockerLabels.ownershipLabelArgs('task-42', ownership)

        then: 'exactly four labels, and every value is one of the three inputs (or the fixed marker)'
        args.count { it == '--label' } == 4

        and:
        args.findAll {
            it != '--label'
        }.collect {
            it.substring(it.indexOf('=') + 1)
        }.toSet() ==
        [
            'true',
            'task-42',
            'tracked',
            'a1b2c3d4e5f6'
        ] as Set
    }

    def "FR8: projectLabelFilter scopes a listing to one project id"() {
        expect:
        FactoryDockerLabels.projectLabelFilter('proj-1') == 'label=com.github.oinsio.gnomish.project=proj-1'
    }

    def "NFR-S1: ObjectOwnership refuses a blank project id"() {
        when:
        new ObjectOwnership(OwnershipMode.TRACKED, projectId)

        then:
        thrown(IllegalArgumentException)

        where:
        projectId << ['', '   ']
    }
}
