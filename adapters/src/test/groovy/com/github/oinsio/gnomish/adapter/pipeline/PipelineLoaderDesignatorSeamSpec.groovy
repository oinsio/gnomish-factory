package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The startup check that spans the tracker adapter's designator rule and the
 * {@code task-branch.base.allowed} list (FR3, UX1 of add-base-ref-resolution, design D5).
 *
 * <p>FR3: a rule extracting designator kind {@code base} with nothing allowed can only ever reject
 * a task's selection, so it is a located load error naming both places — not a stream of parked
 * tasks later. Either half alone is a legitimate configuration.
 */
class PipelineLoaderDesignatorSeamSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    private static final Set<String> EXTRACTS_BASE = ['base'] as Set

    /** A `config.yaml` declaring the github tracker, plus whatever `task-branch:` section is under test. */
    private static String withTracker(String taskBranchSection) {
        'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: github\n  github:\n    repo: owner/repo\n' + taskBranchSection
    }

    // FR3: "Selection rule without allowed bases is a load error"
    def "a base designator rule with nothing allowed fails the load, naming the rule and the allowed list"() {
        given: 'the adapter extracts kind base, and the project allows nothing'
        writePlanOnlyTree(withTracker(''))

        when:
        def outcome = loadConfigurationTree(EXTRACTS_BASE).outcome()

        then:
        def errors = renderedErrors(outcome)
        errors.size() == 1
        errors[0].startsWith('config.yaml: tracker.github.designators.base: ')
        errors[0].contains('task-branch.base.allowed declares no entry')
        errors[0].contains('add a task-branch.base.allowed entry or remove the rule')
    }

    def "a base section declaring no allowed base fails the same way as an absent section"() {
        given:
        writePlanOnlyTree(withTracker('task-branch:\n  base:\n    default: main\n'))

        when:
        def errors = renderedErrors(loadConfigurationTree(EXTRACTS_BASE).outcome())

        then:
        errors == [errors[0]]
        errors[0].startsWith('config.yaml: tracker.github.designators.base: ')
    }

    def "a rule with a non-empty allowed list loads"() {
        given:
        writePlanOnlyTree(withTracker('task-branch:\n  base:\n    allowed:\n      - pattern: release/*\n'))

        when:
        def load = loadConfigurationTree(EXTRACTS_BASE)

        then:
        load.outcome() instanceof LoadOutcome.Loaded
        load.base().allowedBases().entries()*.pattern()*.source() == ['release/*']
    }

    def "an empty allowed list with no rule loads: a project that lets no task choose"() {
        given:
        writePlanOnlyTree(withTracker(''))

        when:
        def load = loadConfigurationTree()

        then:
        load.outcome() instanceof LoadOutcome.Loaded
        load.base().allowedBases().isEmpty()
    }

    def "a rule for another designator kind says nothing about task-branch.base.allowed"() {
        given: 'the adapter extracts kind type, which this version defines no allowed list for'
        writePlanOnlyTree(withTracker(''))

        when:
        def load = loadConfigurationTree(['type'] as Set)

        then:
        load.outcome() instanceof LoadOutcome.Loaded
    }

    // FR3: "No section, no adapter, no kinds" (TrustedTierSections) — with no tracker section at
    // all there is no mapped tracker config to ask, so no adapter can be reporting kind 'base'
    // however the fixture's designatorKinds is wired; the seam has nothing to check and loads clean
    def "with no tracker section at all there is no adapter to name, so the rule never fires"() {
        given:
        writePlanOnlyTree('schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')

        when:
        def load = loadConfigurationTree(EXTRACTS_BASE)

        then:
        load.outcome() instanceof LoadOutcome.Loaded
    }

    def "a tracker section with no type declared names the rule generically too"() {
        given:
        writePlanOnlyTree('schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  abort-threshold: 3\n')

        when:
        def errors = renderedErrors(loadConfigurationTree(EXTRACTS_BASE).outcome())

        then: 'beside the seam error the missing type itself is reported'
        errors.any { it.startsWith('config.yaml: tracker.designators.base: ') }
        errors.any { it.startsWith('config.yaml: tracker.type: ') }
    }
}
