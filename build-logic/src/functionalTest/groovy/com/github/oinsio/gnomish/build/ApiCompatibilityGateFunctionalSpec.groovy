package com.github.oinsio.gnomish.build

import org.gradle.testkit.runner.BuildResult
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.lang.TempDir

import java.nio.file.Path

/**
 * Behavioral verification of the api-compatibility gate — FR1–FR6 of
 * add-functional-api-gate-test, over the gate introduced as FR14 / M5 of
 * add-plugin-architecture.
 *
 * The gate used to be checked by matching substrings of the convention script
 * (`ApiCompatibilityGateSpec`), which proved that certain words existed, not
 * that the gate bit. Here it is RUN: `api-compatibility-gate-conventions` is
 * applied to a miniature java library (`MiniApiProject`) whose surface and
 * baseline this spec mutates scenario by scenario.
 *
 * Ordering is deliberate and load-bearing (design D4): ONE fixture is built in
 * `setupSpec` and the feature methods below run `@Stepwise`, each leaving the
 * fixture in the state the next one expects —
 *
 *   1. baseline generated, gate green      (FR6)
 *   2. member removed, gate fails          (FR2)
 *   3. member restored + one added, green  (FR3)
 *   4. baseline emptied, arming error, re-armed (FR4)
 *   5. outputs invalidated, `check` runs the gate (FR5)
 *
 * A TestKit run is a whole Gradle build, so five isolated fixtures would
 * dominate `check` wall-time for no isolation benefit (NFR-P1). Every run is
 * `--offline` (NFR-R1).
 */
@Stepwise
class ApiCompatibilityGateFunctionalSpec extends Specification {

    @Shared
    @TempDir
    Path fixtureDir

    @Shared
    MiniApiProject project

    def setupSpec() {
        project = new MiniApiProject(fixtureDir)
        project.write()
    }

    def "FR6: the baseline task arms the gate from the current surface"() {
        when: 'the baseline is generated from the current api surface'
        BuildResult update = project.build('updateApiCompatibilityBaseline')

        then: 'baseline jars appear next to the module'
        update.task(':updateApiCompatibilityBaseline').outcome.name() == 'SUCCESS'
        !project.baselineJars().isEmpty()

        and: 'the gate passes against the surface it was just armed with'
        project.build('japicmpApiGate').task(':japicmpApiGate').outcome.name() == 'SUCCESS'
    }

    def "FR2: removing a public method fails the build at the gate"() {
        given: 'the surface loses a public method it was baselined with'
        project.writeApi(MiniApiProject.API_WITHOUT_ANSWER)

        when:
        BuildResult failed = project.buildAndFail('japicmpApiGate')

        then: 'the gate — not something else — fails the build'
        failed.task(':japicmpApiGate').outcome.name() == 'FAILED'

        and: 'the failure is reported as a binary incompatibility (NFR-O1: the japicmp detail is in the message)'
        assert failed.output.contains('Detected binary changes'):
                "the gate failed without naming a binary incompatibility.\njapicmp report:\n${project.japicmpReport()}"
    }

    def "FR3: adding a public method passes without re-baselining"() {
        given: 'the removed method is restored and a new one is added'
        project.writeApi(MiniApiProject.API_WITH_ADDITION)

        when:
        BuildResult result = project.build('japicmpApiGate')

        then: 'an addition is a compatible change — no re-baseline is demanded'
        result.task(':japicmpApiGate').outcome.name() == 'SUCCESS'
        result.task(':updateApiCompatibilityBaseline') == null
    }

    def "FR4: an empty baseline fails as unarmed rather than passing"() {
        given: 'the baseline jars are gone'
        project.emptyBaseline()

        when:
        BuildResult failed = project.buildAndFail('japicmpApiGate')

        then: 'the gate refuses to run instead of silently reporting success'
        failed.task(':japicmpApiGate').outcome.name() == 'FAILED'
        failed.output.contains('No API compatibility baseline')
        failed.output.contains('the gate cannot run')

        cleanup: 're-arm for the scenario that follows'
        project.build('updateApiCompatibilityBaseline')
    }

    def "FR5: check executes the gate rather than skipping it"() {
        given: 'the gate has no up-to-date outputs to coast on'
        project.invalidateGateOutputs()

        when:
        BuildResult result = project.build('check')

        then: 'the gate really ran as part of check — not SKIPPED, not UP-TO-DATE, not absent'
        result.task(':japicmpApiGate')?.outcome?.name() == 'SUCCESS'
    }
}
