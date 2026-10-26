package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.project.ProjectName
import com.github.oinsio.gnomish.app.project.ProjectResolution
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1, FR2, FR7 of add-doctor-command: in collect mode the configuration loader publishes the one
 * resolution of {@code doctor}'s directory it read — the registered clone, or the registry's own
 * refusal with whether {@code --dir} named the directory, or a registry it could not read — so the
 * registration check names the directory without resolving {@code --dir} a second time.
 */
class OperatorConfigProjectResolutionSpec extends Specification {

    @TempDir
    Path tmp

    OperatorConfigLoaderHarness harness
    Path widgets

    def setup() {
        harness = new OperatorConfigLoaderHarness(tmp)
        widgets = harness.gitTree('widgets')
        harness.register('widgets', widgets)
    }

    def "FR1: a registered --dir is published as its clone, the same one the clone bean holds"() {
        when:
        def loaded = harness.load([
            'doctor',
            "--dir=$widgets".toString()
        ])

        then:
        def resolution = resolution(loaded) as ProjectResolution.Registered
        resolution.registeredClone().project() == new ProjectName('widgets')
        resolution.registeredClone() == loaded.context.getBean(com.github.oinsio.gnomish.app.project.RegisteredClone)
    }

    def "FR1, FR2: an unregistered --dir is published with the registry's project add line, named"() {
        given:
        def elsewhere = harness.gitTree('elsewhere').toAbsolutePath().normalize()

        when:
        def resolution = resolution(harness.load([
            'doctor',
            "--dir=$elsewhere".toString()
        ])) as ProjectResolution.Unregistered

        then:
        resolution.dir() == elsewhere
        resolution.named()
        resolution.refusal().contains('is not a registered clone; register it with: gnomish project add')
    }

    def "FR2: without --dir the working directory is published, not named"() {
        when:
        def resolution = resolution(harness.load(['doctor']))

        then:
        resolution instanceof ProjectResolution.Unregistered
        !(resolution as ProjectResolution.Unregistered).named()
        (resolution as ProjectResolution.Unregistered).dir() == Path.of('').toAbsolutePath().normalize()
    }

    def "FR1: a registry that cannot be read is published as unread for the directory"() {
        given:
        harness.projectConfig('widgets', 'factory: [unclosed\n')

        when:
        def resolution = resolution(harness.load([
            'doctor',
            "--dir=$widgets".toString()
        ]))

        then:
        resolution == new ProjectResolution.Unread(widgets.toAbsolutePath().normalize(), true)
    }

    def "FR7: a refused command line publishes no resolution, and neither does #command"() {
        expect:
        !harness.load(args.collect {
            it.replace('WIDGETS', widgets.toString())
        })
        .context.containsBean(OperatorConfigLoader.RESOLUTION_BEAN)

        where:
        command | args
        'doctor --dir=' | ['doctor', '--dir=']
        'run' | ['run', '--dir=WIDGETS']
        'status' | ['status', '--dir=WIDGETS']
        'the empty command line' | []
    }

    private static ProjectResolution resolution(OperatorConfigLoaderHarness.Loaded loaded) {
        loaded.context.getBean(OperatorConfigLoader.RESOLUTION_BEAN, ProjectResolution)
    }
}
