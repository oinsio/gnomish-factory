package com.github.oinsio.gnomish.build

import org.gradle.testkit.runner.BuildResult
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Behavioral verification of the dependency-license gate — FR8, M2 of
 * add-project-license, over the gate of FR4–FR6 (`license-gate-conventions` with
 * the repository's `config/allowed-licenses.json`).
 *
 * The gate is RUN, not read: each scenario points the miniature `:bootstrap`'s
 * runtime classpath at one hand-written third-party module and runs
 * `checkLicense` with the CI job's flags. One fixture serves all three scenarios —
 * each rewrites the only line it depends on — since a TestKit run is a whole
 * Gradle build. Every run is `--offline` (NFR-R1).
 */
class LicenseGateFunctionalSpec extends Specification {

    @Shared
    @TempDir
    Path fixtureDir

    @Shared
    MiniLicenseProject project

    def setupSpec() {
        project = new MiniLicenseProject(fixtureDir)
        project.write()
    }

    def "FR8: a module declaring only LGPL fails the gate, named with its license"() {
        given:
        project.bootstrapDependsOn('lgpl-only')

        when:
        BuildResult failed = project.checkLicenseAndFail()

        then: 'the gate — not something else — fails the build'
        failed.task(':checkLicense').outcome.name() == 'FAILED'

        and: 'the log names the coordinates and the declared license (NFR-O1, UX1)'
        failed.output.contains("${MiniLicenseProject.GROUP}:lgpl-only")
        failed.output.contains('GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1')
    }

    def "FR8: a module declaring EPL-2.0 and LGPL-2.1 passes on its permissive option"() {
        given:
        project.bootstrapDependsOn('dual-epl-lgpl')

        expect: 'one accepted license is enough, with no rule naming the module (M1)'
        project.checkLicense().task(':checkLicense').outcome.name() == 'SUCCESS'
    }

    def "FR8: an Apache spelling variant is normalized and passes"() {
        given:
        project.bootstrapDependsOn('apache-variant')

        expect: 'the normalizer folds the variant onto the canonical name the allowlist spells (FR6)'
        project.checkLicense().task(':checkLicense').outcome.name() == 'SUCCESS'
    }
}
