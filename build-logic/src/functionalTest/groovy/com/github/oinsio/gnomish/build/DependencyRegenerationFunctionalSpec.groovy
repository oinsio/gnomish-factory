package com.github.oinsio.gnomish.build

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.TempDir

import java.nio.file.Path

/**
 * The documented regeneration command of the lockfiles and the verification metadata (FR3, NFR-R1
 * of add-dependency-verification), RUN on a miniature build that locks and verifies the way the
 * real one does ({@link MiniLockProject}): a second run with nothing changed writes nothing new,
 * and a version bump changes only the bumped artifact's entries.
 *
 * {@code @Stepwise} over one fixture, as {@code ApiCompatibilityGateFunctionalSpec} is: each
 * feature leaves the state the next one starts from, and every run is {@code --offline}.
 */
@Stepwise
class DependencyRegenerationFunctionalSpec extends Specification {

    @Shared
    @TempDir
    Path fixtureDir

    @Shared
    MiniLockProject project

    @Shared
    Map<String, List<String>> firstRun

    def setupSpec() {
        project = new MiniLockProject(fixtureDir)
        project.write()
    }

    // Scenario: build/dependency-verification / Idempotent regeneration
    def "FR3, NFR-R1: a second regeneration with no dependency change writes an identical lockfile and metadata"() {
        given: 'the command has run once and pinned both modules'
        project.regenerate()
        firstRun = project.lockState()
        assert firstRun.lockfile.any { it.startsWith('com.example:alpha:1.0=') }
        assert firstRun.metadata.any { it.contains('name="beta" version="1.0"') }

        when:
        project.regenerate()

        then: 'the second run produced an empty diff'
        project.lockState() == firstRun
    }

    // Scenario: build/dependency-verification / Dependency bump produces a reviewable diff
    def "FR3: a version bump changes only the bumped artifact's lockfile and metadata entries"() {
        given: 'alpha is bumped from 1.0 to 1.1, beta is left alone'
        project.useAlpha('1.1')

        when:
        project.regenerate()
        def bumped = project.lockState()

        then: 'the lockfile diff is the alpha line alone'
        changedLines(firstRun.lockfile, bumped.lockfile).every { it.startsWith('com.example:alpha:') }
        bumped.lockfile.any { it.startsWith('com.example:alpha:1.1=') }

        and: 'the metadata diff touches only alpha\'s component, never beta\'s'
        def changed = changedLines(firstRun.metadata, bumped.metadata)
        !changed.isEmpty()
        changed.every { !it.contains('beta') }
        changed.any { it.contains('name="alpha" version="1.1"') }
        bumped.metadata.findAll { it.contains('beta') } == firstRun.metadata.findAll { it.contains('beta') }
    }

    /** Lines present in one list and not the other — the content of a line diff, whitespace kept. */
    private static List<String> changedLines(List<String> before, List<String> after) {
        (before - after) + (after - before)
    }
}
