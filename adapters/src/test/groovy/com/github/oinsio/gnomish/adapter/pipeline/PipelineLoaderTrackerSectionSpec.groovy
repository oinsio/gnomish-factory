package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The tracker seam of the loader (FR17 of add-tracker-port, task 3.2): the optional
 * {@code tracker:} section of config.yaml is graded by the registry of
 * {@code TrackerSubsectionValidator}s the composition root discovered. Absent, it
 * changes nothing; present, its {@code type} must be known and must be matched by
 * exactly its own subsection, with {@code abort-threshold} defaulting to 3. Every
 * seam problem is an ordinary located ConfigError, aggregated one-pass with the rest.
 *
 * <p>Seam mechanics only: these run with an accept-anything registry
 * ({@link TrackerValidatorStub}) so no adapter import crosses the
 * {@code TrackerPortBoundarySpec} gate. The delegated-content half of the
 * "Adapter errors aggregate with core errors" scenario — a real
 * {@code GithubTrackerSubsectionValidator} error aggregating with a core error through
 * the assembled loader — is covered by {@code TrackerAdapterConfigurationSpec}, in
 * {@code adapter.tracker} where importing the concrete validator is permitted.
 *
 * <p>Implements FR17 of add-tracker-port.
 */
class PipelineLoaderTrackerSectionSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    private static final String GITHUB_TRACKER_CONFIG = '''\
schemaVersion: "1"
autonomy:
  attemptLimit: 3
tracker:
  type: github
  github:
    api-url: https://api.github.com
'''

    private static final String UNKNOWN_TYPE_TRACKER_CONFIG =
    'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: bogus\n'

    // "No tracker section" delta-spec scenario: loading succeeds exactly as before
    // and the definition reports no tracker configuration
    def "a tree with no tracker section loads unchanged, with no tracker configuration"() {
        given:
        writeValidTree()

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Loaded
        (outcome as LoadOutcome.Loaded).definition().tracker() == null
    }

    // "Defaulted threshold" delta-spec scenario: a tracker section declaring type but no
    // abort-threshold resolves to 3. Includes a matching github: subsection (task 3.2's
    // seam rule requires one for a known type) so this test proves only the default.
    def "a tracker section with type but no abort-threshold loads with threshold 3"() {
        given:
        writePlanOnlyTree(GITHUB_TRACKER_CONFIG)

        when:
        def outcome = loadTreeWithGithubTracker()

        then:
        outcome instanceof LoadOutcome.Loaded
        def tracker = (outcome as LoadOutcome.Loaded).definition().tracker()
        tracker.type() == 'github'
        tracker.abortThreshold() == 3
    }

    def "a tracker section with an unknown type fails to load with a located error naming it"() {
        given:
        writePlanOnlyTree(UNKNOWN_TYPE_TRACKER_CONFIG)

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('config.yaml', 'tracker.type', "unknown tracker type 'bogus'")
        ]
    }

    // A present tracker section with no type is a located seam error (TrackerConfig/
    // TrackerDto contract: type is never null when the section is present), not a
    // silent success
    def "a tracker section present but missing type fails to load with a located error"() {
        given:
        writePlanOnlyTree('schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  abort-threshold: 3\n')

        when:
        def outcome = loadTreeWithGithubTracker()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('config.yaml', 'tracker.type', 'missing required tracker type')
        ]
    }

    // "Missing subsection" delta-spec scenario
    def "a known tracker type with no matching subsection fails to load with a located error"() {
        given:
        writePlanOnlyTree('schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\ntracker:\n  type: github\n')

        when:
        def outcome = loadTreeWithGithubTracker()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('config.yaml', 'tracker.github', "missing required subsection 'github'")
        ]
    }

    // "Mismatched subsection" delta-spec scenario: the stray jira: key is reported
    // even though github: is present
    def "a tracker section with a stray subsection not matching type fails to load with a located error"() {
        given:
        writePlanOnlyTree(GITHUB_TRACKER_CONFIG + '  jira:\n    project: FOO\n')

        when:
        def outcome = loadTreeWithGithubTracker()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors() == [
            new ConfigError('config.yaml', 'tracker.jira',
            "subsection 'jira' does not match declared tracker type 'github'")
        ]
    }

    // The seam-mechanics half of "Adapter errors aggregate with core errors"
    def "a tracker seam error aggregates with an unrelated core error in one pass"() {
        given: 'an unknown tracker type plus an unrelated structural error (unknown executor)'
        write('config.yaml', UNKNOWN_TYPE_TRACKER_CONFIG)
        write('pipeline.yaml', 'stages:\n  - plan\n')
        write('stages/plan/stage.yaml', '''\
purpose: plan
executor:
  type: bogus-executor
  model: m
instructions: stages/plan/instructions.md
advancement: auto
''')
        write('stages/plan/instructions.md', 'plan\n')

        when:
        def outcome = loadTree()

        then: 'one Invalid outcome carries both the tracker-seam error and the structural error'
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.contains(new ConfigError('config.yaml', 'tracker.type', "unknown tracker type 'bogus'"))
        errors.any {
            it.file() == 'stages/plan/stage.yaml' && it.where() == 'executor.type' &&
            it.message().contains("unknown executor 'bogus-executor'")
        }
    }

    def "a tracker section with a known type and matching subsection loads cleanly"() {
        given:
        writePlanOnlyTree(GITHUB_TRACKER_CONFIG)

        when:
        def outcome = loadTreeWithGithubTracker()

        then:
        outcome instanceof LoadOutcome.Loaded
        (outcome as LoadOutcome.Loaded).definition().tracker().type() == 'github'
    }
}
