package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.ConfigError
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * A {@code command} stage's {@code settings} seen through the loader end to end (FR3, UX2 of
 * add-command-executor): exactly {@code roundTimeout} is accepted, in the shapes the agent-cli
 * rounds accept, and every other key or a malformed value fails the load with one located error
 * naming the stage manifest and the key.
 */
class PipelineLoaderCommandSettingsSpec extends Specification implements PipelineLoaderFixtureSupport {

    static final String MANIFEST = 'stages/gen/stage.yaml'

    @TempDir
    Path root

    void tree(String settingsBlock) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 2\n')
        write('pipeline.yaml', 'stages:\n  - gen\n')
        write('stages/gen/instructions.md', 'generate\n')
        write(MANIFEST, 'purpose: generate sources\nexecutor:\n  type: command\n  command: make gen\n'
                + settingsBlock + 'instructions: stages/gen/instructions.md\nadvancement: auto\n')
    }

    List<ConfigError> errors() {
        def outcome = loadTree()
        assert outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors()
    }

    // FR3: number of seconds and ISO-8601 string, the agent-cli shapes
    def "a command stage declaring roundTimeout: #value loads, the setting carried to the model"() {
        given:
        tree("  settings:\n    roundTimeout: ${value}\n")

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Loaded
        (outcome as LoadOutcome.Loaded).definition().stages()[0].executor().settings() == [roundTimeout: carried]

        where:
        value || carried
        '600' || 600
        '"PT10M"' || 'PT10M'
    }

    // FR3, UX2: only roundTimeout — an agent-cli key is an unknown key here
    // Scenario: executor/command-executor / Unknown settings key fails at load
    def "a command stage's settings key #key fails the load with a located error naming the stage and key"() {
        given:
        tree("  settings:\n    ${key}: ${value}\n")

        expect:
        errors() == [
            new ConfigError(MANIFEST, "executor.settings.${key}".toString(), "unrecognized settings key '${key}'".toString())
        ]

        where:
        key | value
        'allowedTools' | '[Read]'
        'maxTurns' | '3'
    }

    // FR3, UX2: a malformed roundTimeout, under the same contract as the agent-cli shapes
    def "a command stage's malformed roundTimeout (#value) fails the load with a located error"() {
        given:
        tree("  settings:\n    roundTimeout: ${value}\n")

        expect:
        errors() == [
            new ConfigError(MANIFEST, 'executor.settings.roundTimeout',
            "malformed 'roundTimeout': expected a number of seconds or an ISO-8601 duration string")
        ]

        where:
        value << ['soon', 'true', '""']
    }

    // Scenario: pipeline/pipeline-config / Settings key with no value is rejected
    // UX2 (design D10): a key written with no value is YAML null — a located error, never an exception
    def "a command stage's roundTimeout with no value fails the load with a located error"() {
        given:
        tree("  settings:\n    roundTimeout:\n")

        expect:
        errors() == [
            new ConfigError(MANIFEST, 'executor.settings.roundTimeout', "missing value for 'roundTimeout'")
        ]
    }
}
