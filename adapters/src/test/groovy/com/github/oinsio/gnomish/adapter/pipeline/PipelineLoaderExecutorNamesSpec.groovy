package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * The executor names a law may use, judged at load (FR1, FR13, UX2 of
 * migrate-verify-to-executor-contract; design D18, "The six built-in names have one owner"): a
 * declaration under one of the six built-in names is a located error naming it as a built-in's, and a
 * check naming an executor outside the six and the law's own declarations is a located error listing
 * exactly that union — the built-ins' names offered whether or not the law names them. Both are judged
 * from the one set of the six names in {@code :domain}, in the loader's single pass.
 */
class PipelineLoaderExecutorNamesSpec extends Specification implements PipelineLoaderFixtureSupport {

    @TempDir
    Path root

    private static final String DECLARED = '''schemaVersion: "1"
autonomy:
  attemptLimit: 3
executors:
  %s:
    type: program
    program: [./program]
'''

    // Scenario: pipeline/pipeline-config / Defaulted github provider absent from the registry is a located error
    def "a law that declares an executor shadowing github-actions, beside a check defaulting to it, is a located error naming the built-in"() {
        given: 'the build stage keeps its external check with no provider; the law declares github-actions'
        writeValidTree()
        write('config.yaml', DECLARED.formatted('github-actions'))

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'config.yaml' && it.where() == 'executors.github-actions' &&
            it.message().contains("'github-actions' is a built-in's name")
        }
    }

    // FR14, D12 of extract-agent-round-runner: 'claude' is the reserved name of the agent entry
    // Scenario: pipeline/pipeline-config / An agent block resolves to the reserved executor name
    def "FR14: a law that declares an executor named claude is a located load error naming the built-in"() {
        given:
        writeValidTree()
        write('config.yaml', DECLARED.formatted('claude'))

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'config.yaml' && it.where() == 'executors.claude' &&
            it.message().contains("'claude' is a built-in's name")
        }
    }

    // Scenario: pipeline/pipeline-config / Unknown provider is a located load error
    def "an external check naming an executor of neither the built-ins nor the law is a located error listing their union"() {
        given:
        writeValidTree()
        write('config.yaml', DECLARED.formatted('acme'))
        write('stages/build/stage.yaml', buildManifest().replace('checkId: ci\n', 'checkId: ci\n    provider: sonar\n'))

        when:
        def outcome = loadTree()

        then:
        outcome instanceof LoadOutcome.Invalid
        (outcome as LoadOutcome.Invalid).errors().any {
            it.file() == 'stages/build/stage.yaml' && it.where() == 'verify[1].provider' &&
            it.message() == "check 'ci' names unknown executor 'sonar'; the registry's executors are " +
            'files-exist, shell, github-actions, scm-status, http, command, acme'
        }
    }

    def "an explicit executor form naming a law declaration or a built-in loads without a name error"() {
        given:
        writeValidTree()
        write('config.yaml', DECLARED.formatted('acme'))
        write('stages/build/stage.yaml', buildManifest().replace('''  - type: external
    checkId: ci
    interval: 5s
    timeout: 60s
''', '''  - executor: acme
    checkId: gate
    verifyIn: fresh-box
    interval: 5s
    timeout: 60s
'''))

        when:
        def outcome = loadTree()

        then: 'no error names the executor as unknown'
        !(outcome instanceof LoadOutcome.Invalid) || (outcome as LoadOutcome.Invalid).errors().every {
            !it.message().contains('unknown executor')
        }
    }
}
