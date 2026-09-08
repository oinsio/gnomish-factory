package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.baseref.BranchRole
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The {@code task-branch.base} section of {@code config.yaml} (FR1, UX1 of add-base-ref-resolution,
 * design D16): the allowed bases and the configured default, parsed, validated and compiled into
 * the {@code :baseref} value types by the same single pass that loads everything else.
 *
 * <p>FR1: the section's shape — the {@code type} discriminator defaulting to {@code patterns},
 * {@code default}, and an {@code allowed} list of patterns with roles defaulting to development.
 *
 * <p>UX1: every problem is a located {@code ConfigError} aggregated with the rest, never an
 * exception and never a silent default.
 */
class PipelineLoaderBaseSectionSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    private static String tree(String taskBranchSection) {
        'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n' + taskBranchSection
    }

    // FR1: "Settled shape loads" — the compiled list, the default and the roles are all exposed
    def "the settled shape loads and exposes the compiled allowed bases, the roles and the default"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    type: patterns
    default: main
    allowed:
      - pattern: main
      - pattern: release/*
        role: release
'''))

        when:
        def load = loadConfigurationTree()

        then: 'the task tier is valid and the trusted tier carries the compiled definition'
        load.outcome() instanceof LoadOutcome.Loaded
        load.base().defaultRef() == 'main'
        load.base().allowedBases().entries()*.pattern()*.source() == ['main', 'release/*']
        load.base().allowedBases().entries()*.role() == [
            BranchRole.DEVELOPMENT,
            BranchRole.RELEASE
        ]

        and: 'the patterns are compiled, not text: the series matches and its neighbours do not'
        load.base().allowedBases().match('release/1.18').present
        load.base().allowedBases().match('experiments/foo').empty
    }

    // FR1: "No task-branch section" — absent is valid, and allows nothing with no default
    def "a tree with no task-branch section loads exactly as before, allowing nothing and defaulting to nothing"() {
        given:
        writePlanOnlyTree('schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')

        when:
        def load = loadConfigurationTree()

        then:
        load.outcome() instanceof LoadOutcome.Loaded
        load.base().allowedBases().isEmpty()
        load.base().defaultRef() == null

        and: 'which is the definition a project that declared nothing has'
        load.base() == BaseDefinition.none()
    }

    // FR1: a task-branch section that declares no base subsection is the same as declaring nothing
    def "a task-branch section with no base subsection is the zero-configuration definition"() {
        given:
        writePlanOnlyTree(tree('task-branch:\n'))

        when:
        def load = loadConfigurationTree()

        then:
        load.outcome() instanceof LoadOutcome.Loaded
        load.base() == BaseDefinition.none()
    }

    // FR1: a section declaring no type is a patterns section — the discriminator defaults
    def "an omitted type is patterns, and an omitted role is development"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    allowed:
      - pattern: main
'''))

        when:
        def load = loadConfigurationTree()

        then:
        load.outcome() instanceof LoadOutcome.Loaded
        load.base().allowedBases().entries()*.role() == [BranchRole.DEVELOPMENT]
    }

    // FR1: "Unknown discriminator is a load error"
    def "an unknown type is a located error naming the discriminator"() {
        given:
        writePlanOnlyTree(tree('task-branch:\n  base:\n    type: script\n'))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            "config.yaml: task-branch.base.type: unknown base type 'script'; known types are patterns"
        ]
    }

    // FR1: "Default outside the allowed bases is a load error"
    def "a default matching no allowed pattern is a located error naming what it failed"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    default: develop
    allowed:
      - pattern: release/*
'''))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            'config.yaml: task-branch.base.default: '
            + "default 'develop' matches no task-branch.base.allowed pattern; the allowed bases are release/*"
        ]
    }

    // FR1: a project that allows nothing accepts no selection, so nothing bounds its default
    def "a default with nothing allowed is accepted"() {
        given:
        writePlanOnlyTree(tree('task-branch:\n  base:\n    default: develop\n'))

        when:
        def load = loadConfigurationTree()

        then:
        load.outcome() instanceof LoadOutcome.Loaded
        load.base().defaultRef() == 'develop'
        load.base().allowedBases().isEmpty()
    }

    // FR1: patterns compile at load, so a malformed one is a located error, not a claim-time surprise
    def "an invalid allowed pattern is a located error naming the violated rule"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    allowed:
      - pattern: release/../secrets
'''))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        def errors = renderedErrors(outcome)
        errors.size() == 1
        errors[0].startsWith(
                "config.yaml: task-branch.base.allowed[0].pattern: "
                + "invalid allowed-base pattern 'release/../secrets': ")
        errors[0].contains("'..'")
    }

    def "an allowed entry with no pattern is a located error"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    allowed:
      - role: release
'''))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            'config.yaml: task-branch.base.allowed[0].pattern: missing required allowed-base pattern'
        ]
    }

    def "an empty allowed entry is a located error"() {
        given:
        writePlanOnlyTree(tree('task-branch:\n  base:\n    allowed:\n      - \n'))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            'config.yaml: task-branch.base.allowed[0]: allowed base is empty'
        ]
    }

    // FR1: an unknown role
    def "an unknown role is a located error naming the accepted roles"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    allowed:
      - pattern: main
        role: staging
'''))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            'config.yaml: task-branch.base.allowed[0].role: '
            + "unknown allowed-base role 'staging'; known roles are development, release"
        ]
    }

    // FR1: the vocabulary is the tokens an author writes, and only those
    def "a role token is matched exactly, not case-insensitively"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    allowed:
      - pattern: main
        role: RELEASE
'''))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            'config.yaml: task-branch.base.allowed[0].role: '
            + "unknown allowed-base role 'RELEASE'; known roles are development, release"
        ]
    }

    // FR1: "Unknown keys are not ignored"
    def "an unknown key in the section is a located error"() {
        given:
        writePlanOnlyTree(tree('task-branch:\n  base:\n    defualt: main\n'))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            "config.yaml: defualt: unknown field 'defualt'"
        ]
    }

    def "a stray selection rule in the section is an unknown key, not adapter configuration"() {
        given:
        writePlanOnlyTree(tree('task-branch:\n  base:\n    select:\n      label: "^base/(.*)$"\n'))

        when:
        def outcome = loadConfigurationTree().outcome()

        then:
        renderedErrors(outcome) == [
            "config.yaml: select: unknown field 'select'"
        ]
    }

    // FR1, D16: "The earlier draft shape is not an alias" — the root-level key is gone
    def "a root-level base section is an unknown key, not the section under its old name"() {
        given:
        writePlanOnlyTree(tree('base:\n  default: main\n'))

        when:
        def load = loadConfigurationTree()

        then: 'the key is refused, and nothing is read from it'
        renderedErrors(load.outcome()) == [
            "config.yaml: base: unknown field 'base'"
        ]
        load.base() == BaseDefinition.none()
    }

    // FR1, D16: "The earlier draft shape is not an alias" — `menu` is no alias for `allowed`
    def "a menu key inside the section is an unknown key, not an alias for allowed"() {
        given:
        writePlanOnlyTree(tree('task-branch:\n  base:\n    menu:\n      - pattern: main\n'))

        when:
        def load = loadConfigurationTree()

        then:
        renderedErrors(load.outcome()) == [
            "config.yaml: menu: unknown field 'menu'"
        ]
        load.base() == BaseDefinition.none()
    }

    // UX1: one pass reports every problem in the section together
    def "several problems in the section are reported in one pass"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    type: script
    allowed:
      - pattern: "he:re"
      - pattern: main
        role: staging
'''))

        when:
        def errors = renderedErrors(loadConfigurationTree().outcome())

        then:
        errors.size() == 3
        errors[0].startsWith('config.yaml: task-branch.base.type:')
        errors[1].startsWith('config.yaml: task-branch.base.allowed[0].pattern:')
        errors[2].startsWith('config.yaml: task-branch.base.allowed[1].role:')
    }

    // UX1: a broken list never manufactures a second, misleading problem about the default
    def "a default is not held against allowed bases that did not compile"() {
        given:
        writePlanOnlyTree(tree('''\
task-branch:
  base:
    default: main
    allowed:
      - pattern: "he:re"
'''))

        when:
        def errors = renderedErrors(loadConfigurationTree().outcome())

        then:
        errors.size() == 1
        errors[0].startsWith('config.yaml: task-branch.base.allowed[0].pattern:')
    }

    // UX1: the section's errors aggregate with problems that have nothing to do with it
    def "base errors aggregate with unrelated core errors in one pass"() {
        given: 'a missing schemaVersion, an empty pipeline, and an unknown base type'
        write('config.yaml', 'task-branch:\n  base:\n    type: script\n')
        write('pipeline.yaml', 'stages: []\n')

        when:
        def errors = renderedErrors(loadConfigurationTree().outcome())

        then: 'all three are reported together'
        errors.any { it.startsWith('config.yaml: task-branch.base.type:') }
        errors.any { it.contains('version') }
        errors.any { it.contains('declares no stages') }
    }

    // FR1: a config.yaml that will not parse silences its own base tier, and nothing else
    def "a malformed config.yaml yields the parse error and the zero-configuration definition"() {
        given:
        writePlanOnlyTree('task-branch: [unclosed\n')

        when:
        def load = loadConfigurationTree()

        then:
        load.outcome() instanceof LoadOutcome.Invalid
        load.base().allowedBases().isEmpty()
        load.base().defaultRef() == null
    }
}
