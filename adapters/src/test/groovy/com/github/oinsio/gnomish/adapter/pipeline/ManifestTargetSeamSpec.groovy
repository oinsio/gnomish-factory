package com.github.oinsio.gnomish.adapter.pipeline

import com.github.oinsio.gnomish.adapter.check.http.HttpCheckParamsValidator
import com.github.oinsio.gnomish.app.ConnectionProfiles
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.httpegress.OperatorEgress
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR15, FR17, UX4 of migrate-verify-to-executor-contract, design D16 as amended 2026-11-09 (the
 * law-medium bullet): the loader judges every stage manifest's {@code http} target against the
 * operator's {@code factory.egress.in-process} scope it carries on {@link ConnectionAdmission}, and
 * a request nobody grants is a located load error at the target's line, aggregated with every
 * other load problem — not a failure at the check's first poll.
 */
class ManifestTargetSeamSpec extends Specification implements GnomishTreeWriter {

    @TempDir
    Path root

    private LoadOutcome load(String url, List<String> inProcess) {
        write('config.yaml', 'schemaVersion: "1"\nautonomy:\n  attemptLimit: 3\n')
        write('pipeline.yaml', 'stages:\n  - verify\n')
        write('stages/verify/instructions.md', 'verify it\n')
        write('stages/verify/stage.yaml', """\
purpose: verify the work
outputs:
  - id: report
executor:
  type: agent-cli
  model: some-model
instructions: stages/verify/instructions.md
verify:
  - type: command
    command: echo ok
  - type: external
    checkId: quality-gate
    provider: http
    params:
      url: ${url}
    interval: 5s
    timeout: 60s
advancement: auto
""")
        PipelineLoader.loadConfiguration(root, [:], [http: new HttpCheckParamsValidator()], AgentSettingsValidator.UNJUDGED,
        new ConnectionAdmission(ConnectionProfiles.none(), [:], new OperatorEgress(inProcess)), [] as Set)
        .outcome()
    }

    // FR17, UX4: the law requests, the operator grants, and the loader says so at the request's line
    def "a manifest target nobody grants is a located load error at its url"() {
        when:
        def outcome = load('https://sonar.example.com/api/qualitygates', ['ci.example.com'])

        then:
        outcome instanceof LoadOutcome.Invalid
        def errors = (outcome as LoadOutcome.Invalid).errors()
        errors.size() == 1
        errors[0].file() == 'stages/verify/stage.yaml'
        errors[0].where() == 'verify[1].params.url'
        errors[0].message().contains("host 'sonar.example.com'")
        errors[0].message().contains('factory.egress.in-process')
    }

    def "a manifest target the in-process scope grants loads"() {
        expect:
        load('https://sonar.example.com/api/qualitygates', ['sonar.example.com']) instanceof LoadOutcome.Loaded
    }

    // The seam reads every http check, under either manifest form, and no other check
    def "the seam judges every http check, located by its manifest form, and nothing else"() {
        given:
        def refused = [url: 'https://evil.example.org/x']
        def limits = new AutonomyLimits(1)
        def stage = new StageDefinition('verify', 'purpose', [], [], null, 'instructions.md', [
            VerifyChecks.command('echo ok'),
            VerifyChecks.external('gate', 'http', refused, Duration.ofSeconds(1), Duration.ofSeconds(5),
            VerifyCheck.TimeoutClass.QUALITY, []),
            VerifyChecks.external('ci.yml', 'github-actions', refused, Duration.ofSeconds(1), Duration.ofSeconds(5),
            VerifyCheck.TimeoutClass.QUALITY, []),
            new VerifyCheck.Executor(VerifyCheck.Token.EXECUTOR, 'http', 'probe', refused, Duration.ZERO,
            Duration.ZERO, VerifyCheck.TimeoutClass.QUALITY, [], VerifyCheck.VerifyIn.SAME_BOX, Threshold.ERROR, []),
        ], limits, null)

        when:
        def errors = ManifestTargetSeam.judge([stage, stage], OperatorEgress.none())

        then:
        errors*.where() == [
            'verify[1].params.url',
            'verify[3].settings.url',
            'verify[1].params.url',
            'verify[3].settings.url'
        ]
        errors*.file().unique() == ['stages/verify/stage.yaml']
    }
}
