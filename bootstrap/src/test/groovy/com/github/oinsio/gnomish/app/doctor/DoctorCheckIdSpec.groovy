package com.github.oinsio.gnomish.app.doctor

import spock.lang.Specification

/**
 * FR1, FR3, FR5, FR8 of add-doctor-command: the check list is the fourteen checks of FR1 followed by
 * FR8's hooks-path check and FR3's online tracker probe, in report order, each with a stable id the JSON document will carry and
 * the name its line shows. Pinned whole, so a renamed id fails here rather than in a consumer.
 */
class DoctorCheckIdSpec extends Specification {

    def "FR5: the ids and names are the stable vocabulary, in report order"() {
        expect:
        DoctorCheckId.values().collect { [it.id(), it.displayName()] } == [
            [
                'factory-version',
                'factory version'
            ],
            [
                'git-floor',
                'git version floor'
            ],
            [
                'configuration',
                'operator configuration'
            ],
            [
                'project-registration',
                'project registration'
            ],
            [
                'ref-format',
                'clone ref backend'
            ],
            ['pipeline', 'pipeline law'],
            ['secrets', 'declared secret'],
            [
                'agent-credential',
                'agent credential'
            ],
            ['docker', 'Docker runtime'],
            [
                'sandbox-image',
                'sandbox image'
            ],
            ['guard-image', 'guard image'],
            [
                'agent-cli',
                'agent CLI binary'
            ],
            [
                'launcher-helper',
                'process-group launcher'
            ],
            [
                'legacy-layout',
                'pre-registry layout'
            ],
            [
                'hooks-path',
                'clone hooks path'
            ],
            [
                'tracker-probe',
                'tracker read probe'
            ],
        ]
    }

    def "FR1, FR3, FR8: fourteen checks of FR1 come first, then the hooks-path check of FR8, then FR3's probe"() {
        expect:
        DoctorCheckId.values().size() == 16
        DoctorCheckId.values()[14] == DoctorCheckId.HOOKS_PATH
        DoctorCheckId.values().last() == DoctorCheckId.TRACKER_PROBE
    }

    def "FR3: the tracker read probe is the one online check"() {
        expect:
        DoctorCheckId.values().findAll {
            it.online()
        } == [DoctorCheckId.TRACKER_PROBE]
    }
}
