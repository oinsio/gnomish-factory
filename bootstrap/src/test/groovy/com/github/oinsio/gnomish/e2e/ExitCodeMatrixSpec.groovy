package com.github.oinsio.gnomish.e2e

import com.github.oinsio.gnomish.adapter.agent.FakeAgentSupport
import java.nio.file.Path

/**
 * The exit-code matrix (task 9.3): scenarios pinning FR12's exit-code table and
 * FR13's EOF semantics against the real {@code gnomish run} process, complementing the
 * reference journey ({@link ReferenceE2ESessionSpec}, exits 10, 11, 0) and the harness smoke test
 * ({@link E2eProcessHarnessSmokeSpec}). {@code Aborted} (exit 12) needs a breaking
 * persistence fake and is covered elsewhere (in-process, not here).
 *
 * <ul>
 *   <li>stdin closed, pipeline finishes without escalation or checkpoint &rarr; 0
 *   <li>usage error (bad flags) &rarr; 2
 *   <li>broken pipeline ({@code .gnomish/} invalid) &rarr; 3
 * </ul>
 *
 * <p>The park exits, 10 and 11, are outcome exits since make-run-headless and live in
 * {@link ParkExitCodeSpec}; no exit code of {@code run} comes from an end of input.
 *
 * <p>The gnome and the judge are the fake agent (FR6 of remove-interactive-console); nothing
 * reads stdin (FR7 of make-run-headless).
 *
 * <p>Implements M1, FR12, FR13 of add-manual-run.
 */
class ExitCodeMatrixSpec extends AbstractE2eProcessSpec {

    // FR5 of remove-interactive-console, scenario "Script too short": with stdin closed before the
    //     run starts and a pipeline that neither escalates nor checkpoints, nothing reads stdin —
    //     the run exits 0, prints no prompt, and the retired code 4 is unreachable.
    def "closed stdin and a pipeline with no prompt on its path exits 0"() {
        given: 'the fake agent plays one clean round, and stdin is already closed'
        def agent = FakeAgentSupport.wrapperFor('plain-round')
        Path root = E2eFixture.autoAdvanceRoot()
        // plain-round -> Completed; files_exist passes on the fixture's marker.txt;
        // advancement: auto -> the pipeline completes with no checkpoint.
        List<String> script = []

        when:
        def result = harness.run(
                root,
                agent,
                [
                    '--dir=' + root,
                    '--task=script too short',
                    '--mode=in-place'
                ],
                script)

        then: 'FR5: success, not the retired code 4'
        result.exitCode() == 0

        and: 'the pipeline ran to its end and no operator prompt was printed'
        def output = result.stdout() + result.stderr()
        output.contains('Stage: pipeline complete')
        !output.contains('Press Enter')
        !output.contains('The gnome asked:')
        !output.contains('Awaiting approval')
    }

    def "usage error exits 2 without any dialog"() {
        given: 'neither --task nor --task-file is supplied'
        List<String> noArgsNeeded = []

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--dir=' + E2eFixture.projectRoot()
                ],
                noArgsNeeded)

        then: 'FR12: usage error exit code'
        result.exitCode() == 2

        and: 'the message names the missing/conflicting flag'
        (result.stdout() + result.stderr()).contains('--task')
    }

    def "NFR-O1, FR16 of fix-operator-blockers: a usage error is one stderr line, with no framework trace"() {
        given: 'the subcommand handed an option it does not accept'
        List<String> noArgsNeeded = []

        when:
        def result = harness.run(
                E2eFixture.projectRoot(),
                [
                    '--dir=' + E2eFixture.projectRoot(),
                    '--task=x',
                    '--bogus=1'
                ],
                noArgsNeeded)

        then:
        result.exitCode() == 2

        and: 'stderr carries the message exactly once, and nothing else'
        def stderrLines = result.stderr().readLines().findAll { !it.isBlank() }
        stderrLines.size() == 1
        stderrLines[0].startsWith("unknown option --bogus for 'gnomish run'; accepted: ")

        and: 'the framework adds no failure record of its own on stdout'
        !result.stdout().contains('Application run failed')
        !result.stdout().contains('UsageException')
    }

    def "broken pipeline exits 3 before any dialog"() {
        given: 'a fixture whose plan stage references a missing instructions.md'
        Path brokenRoot = E2eFixture.brokenRoot()

        when:
        def result = harness.run(
                brokenRoot,
                [
                    '--dir=' + brokenRoot,
                    '--task=irrelevant, load fails first',
                    '--mode=in-place'
                ],
                [])

        then: 'FR12: pipeline-load-failure exit code'
        result.exitCode() == 3

        and: 'the loader errors are printed as-is (no prompt reached)'
        (result.stdout() + result.stderr()).contains('instructions.md')
    }

    // FR3, NFR-O1, D5 of remove-interactive-console: an `external` check on a provider this
    //     factory has no `factory.check.<provider>` section for is a load failure — exit 3, the
    //     located error naming the section to write, and no stage ever started.
    def "unconfigured check provider exits 3 before any dialog"() {
        given: 'a fixture whose one stage declares an external check on github, with no factory.check.github'
        Path root = E2eFixture.unconfiguredProviderRoot()

        when:
        def result = harness.run(
                root,
                [
                    '--dir=' + root,
                    '--task=irrelevant, load fails first',
                    '--mode=in-place'
                ],
                [])

        then: 'FR3: pipeline-load-failure exit code'
        result.exitCode() == 3

        and: 'NFR-O1: the located error names the stage manifest, the field and the missing section'
        def output = result.stdout() + result.stderr()
        output.contains('stages/work/stage.yaml')
        output.contains('verify[0].provider')
        output.contains("check provider 'github' has no factory.check.github section")

        and: 'no stage ran and no operator prompt was printed'
        !output.contains("Stage '")
        !output.contains('Press Enter')
    }
}
